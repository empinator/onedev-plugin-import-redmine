package com.devcharly.onedev.plugin.imports.redmine;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.ws.rs.client.Client;

import org.apache.http.client.utils.URIBuilder;
import org.joda.time.format.ISODateTimeFormat;

import com.fasterxml.jackson.databind.JsonNode;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.IssueManager;
import io.onedev.server.entitymanager.MilestoneManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.entitymanager.UserManager;
import io.onedev.server.entityreference.ReferenceMigrator;
import io.onedev.server.model.Issue;
import io.onedev.server.model.IssueComment;
import io.onedev.server.model.IssueField;
import io.onedev.server.model.IssueSchedule;
import io.onedev.server.model.Milestone;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.model.support.LastUpdate;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.inputspec.InputSpec;
import io.onedev.server.model.support.inputspec.choiceinput.choiceprovider.Choice;
import io.onedev.server.model.support.inputspec.choiceinput.choiceprovider.SpecifiedChoices;
import io.onedev.server.model.support.issue.field.spec.ChoiceField;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.util.JerseyUtils;
import io.onedev.server.util.JerseyUtils.PageDataConsumer;

public class ImportUtils {

	static final String NAME = "Redmine";

	static IssueImportOption buildImportOption(ImportServer server, Collection<String> redmineProjects, TaskLogger logger) {
		IssueImportOption importOption = new IssueImportOption();
		return importOption;
	}

	@Nullable
	static User getUser(Client client, ImportServer importSource,
			Map<String, Optional<User>> users, String login, TaskLogger logger) {
		Optional<User> userOpt = users.get(login);
		if (userOpt == null) {
			String apiEndpoint = importSource.getApiEndpoint("/users/" + login + ".json");
			String email = JerseyUtils.get(client, apiEndpoint, logger).get("user").get("mail").asText(null);
			if (email != null)
				userOpt = Optional.ofNullable(OneDev.getInstance(UserManager.class).findByEmail(email));
			else
				userOpt = Optional.empty();
			users.put(login, userOpt);
		}
		return userOpt.orElse(null);
	}

	static ImportResult importIssues(ImportServer server, String redmineProject, Project oneDevProject,
			boolean useExistingIssueNumbers, IssueImportOption importOption, Map<String, Optional<User>> users,
			boolean dryRun, TaskLogger logger) {
		Client client = server.newClient();
		try {
			String redmineProjectId = getRedmineProjectId(redmineProject);
			Set<String> nonExistentMilestones = new HashSet<>();
			Set<String> nonExistentLogins = new HashSet<>();
			Map<String, Milestone> milestoneMappings = new HashMap<>();
			Map<String, String> versionsMappings = new HashMap<>();
			Map<String, String> statusesMappings = new HashMap<>();
			Set<String> closedStatuses = new HashSet<>();

			for (Milestone milestone: oneDevProject.getMilestones())
				milestoneMappings.put(milestone.getName(), milestone);

			String versionsApiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/versions.json");
			for (JsonNode versionNode: list(client, versionsApiEndpoint, "versions", logger))
				versionsMappings.put(versionNode.get("id").asText(), versionNode.get("name").asText());

			String statusesApiEndpoint = server.getApiEndpoint("/issue_statuses.json");
			for (JsonNode statusNode: list(client, statusesApiEndpoint, "issue_statuses", logger)) {
				statusesMappings.put(statusNode.get("id").asText(), statusNode.get("name").asText());
				if (statusNode.has("is_closed") && statusNode.get("is_closed").asBoolean())
					closedStatuses.add(statusNode.get("name").asText());
			}

			importIssueCategories(server, redmineProject, importOption, dryRun, logger);

			String initialIssueState = getIssueSetting().getInitialStateSpec().getName();

			List<Issue> issues = new ArrayList<>();

			Map<Long, Long> issueNumberMappings = new HashMap<>();

			AtomicInteger numOfImportedIssues = new AtomicInteger(0);
			PageDataConsumer pageDataConsumer = new PageDataConsumer() {

				@Override
				public void consume(List<JsonNode> pageData) throws InterruptedException {
					for (JsonNode issueNode: pageData) {
						if (Thread.interrupted())
							throw new InterruptedException();

						Map<String, String> extraIssueInfo = new LinkedHashMap<>();

						Issue issue = new Issue();
						issue.setProject(oneDevProject);
						issue.setTitle(issueNode.get("subject").asText());
						issue.setDescription(issueNode.get("description").asText(null));
						issue.setNumberScope(oneDevProject.getForkRoot());

						Long oldNumber = issueNode.get("id").asLong();
						Long newNumber;
						if (dryRun || useExistingIssueNumbers)
							newNumber = oldNumber;
						else
							newNumber = OneDev.getInstance(IssueManager.class).getNextNumber(oneDevProject);
						issue.setNumber(newNumber);
						issueNumberMappings.put(oldNumber, newNumber);

						if (closedStatuses.contains(issueNode.get("status").get("name").asText())) {
							issue.setState("Rejected".equals(issueNode.get("status").get("name").asText())
									? importOption.getRejectedIssueState()
									: importOption.getClosedIssueState());
						} else
							issue.setState(initialIssueState);

						if (issueNode.hasNonNull("fixed_version")) {
							String milestoneName = issueNode.get("fixed_version").get("name").asText();
							Milestone milestone = milestoneMappings.get(milestoneName);
							if (milestone != null) {
								IssueSchedule schedule = new IssueSchedule();
								schedule.setIssue(issue);
								schedule.setMilestone(milestone);
								issue.getSchedules().add(schedule);
							} else {
								extraIssueInfo.put("Milestone", milestoneName);
								nonExistentMilestones.add(milestoneName);
							}
						}

						String login = issueNode.get("author").get("id").asText(null);
						User user = getUser(client, server, users, login, logger);
						if (user != null) {
							issue.setSubmitter(user);
						} else {
							issue.setSubmitter(OneDev.getInstance(UserManager.class).getUnknown());
							nonExistentLogins.add(issueNode.get("author").get("name").asText());
						}

						issue.setSubmitDate(ISODateTimeFormat.dateTimeNoMillis()
								.parseDateTime(issueNode.get("created_on").asText())
								.toDate());

						LastUpdate lastUpdate = new LastUpdate();
						lastUpdate.setActivity("Opened");
						lastUpdate.setDate(issue.getSubmitDate());
						lastUpdate.setUser(issue.getSubmitter());
						issue.setLastUpdate(lastUpdate);

						JsonNode assigneeNode = issueNode.get("assigned_to");
						if (assigneeNode != null) {
							IssueField assigneeField = new IssueField();
							assigneeField.setIssue(issue);
							assigneeField.setName(importOption.getAssigneesIssueField());
							assigneeField.setType(InputSpec.USER);

							login = assigneeNode.get("id").asText();
							user = getUser(client, server, users, login, logger);
							if (user != null) {
								assigneeField.setValue(user.getName());
								issue.getFields().add(assigneeField);
							} else {
								nonExistentLogins.add(login);
							}
						}

						// category --> custom field "Category"
						JsonNode categoryNode = issueNode.get("category");
						if (categoryNode != null) {
							String category = categoryNode.get("name").asText();
							issue.setFieldValue(importOption.getCategoryIssueField(), category);
						}

						String apiEndpoint = server.getApiEndpoint("/issues/" + oldNumber + ".json?include=journals");
						JsonNode journalsNode = JerseyUtils.get(client, apiEndpoint, logger).get("issue").get("journals");
						for (JsonNode journalNode: journalsNode) {
							JsonNode notesNode = journalNode.get("notes");
							String notes = (notesNode != null) ? notesNode.asText() : "";
							if (notes.isEmpty())
								continue;

							IssueComment comment = new IssueComment();
							comment.setIssue(issue);
							comment.setContent(notes);
							comment.setDate(ISODateTimeFormat.dateTimeNoMillis()
									.parseDateTime(journalNode.get("created_on").asText())
									.toDate());

							login = journalNode.get("user").get("id").asText();
							user = getUser(client, server, users, login, logger);
							if (user != null) {
								comment.setUser(user);
							} else {
								comment.setUser(OneDev.getInstance(UserManager.class).getUnknown());
								nonExistentLogins.add(login);
							}


							issue.getComments().add(comment);
						}

						issue.setCommentCount(issue.getComments().size());

						if (!extraIssueInfo.isEmpty()) {
							StringBuilder builder = new StringBuilder("|");
							for (String key: extraIssueInfo.keySet())
								builder.append(key).append("|");
							builder.append("\n|");
							extraIssueInfo.keySet().stream().forEach(it->builder.append("---|"));
							builder.append("\n|");
							for (String value: extraIssueInfo.values())
								builder.append(value).append("|");

							if (issue.getDescription() != null)
								issue.setDescription(builder.toString() + "\n\n" + issue.getDescription());
							else
								issue.setDescription(builder.toString());
						}
						issues.add(issue);
					}
					logger.log("Imported " + numOfImportedIssues.addAndGet(pageData.size()) + " issues");
				}

			};

			String apiEndpoint = server.getApiEndpoint("/issues.json?project_id=" + redmineProjectId + "&status_id=*&sort=id");
			list(client, apiEndpoint, "issues", pageDataConsumer, logger);

			if (!dryRun) {
				ReferenceMigrator migrator = new ReferenceMigrator(Issue.class, issueNumberMappings);
				Dao dao = OneDev.getInstance(Dao.class);
				for (Issue issue: issues) {
					if (issue.getDescription() != null)
						issue.setDescription(migrator.migratePrefixed(issue.getDescription(), "#"));

					OneDev.getInstance(IssueManager.class).save(issue);
					for (IssueSchedule schedule: issue.getSchedules())
						dao.persist(schedule);
					for (IssueField field: issue.getFields())
						dao.persist(field);
					for (IssueComment comment: issue.getComments()) {
						comment.setContent(migrator.migratePrefixed(comment.getContent(),  "#"));
						dao.persist(comment);
					}
				}
			}

			ImportResult result = new ImportResult();
			result.nonExistentLogins.addAll(nonExistentLogins);
			result.nonExistentMilestones.addAll(nonExistentMilestones);

			if (numOfImportedIssues.get() != 0)
				result.issuesImported = true;

			return result;
		} finally {
			client.close();
		}
	}

	static void importVersions(ImportServer server, String redmineProject, Project oneDevProject,
			boolean dryRun, TaskLogger logger) {
		Client client = server.newClient();
		try {
			String redmineProjectId = getRedmineProjectId(redmineProject);

			List<Milestone> milestones = new ArrayList<>();
			logger.log("Importing versions from project " + redmineProject + "...");
			String apiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/versions.json");
			for (JsonNode versionNode: list(client, apiEndpoint, "versions", logger)) {
				Milestone milestone = new Milestone();
				milestone.setName(versionNode.get("name").asText());
				JsonNode descriptionNode = versionNode.get("description");
				if (descriptionNode != null)
					milestone.setDescription(descriptionNode.asText(null));
				milestone.setProject(oneDevProject);
				JsonNode dueDateNode = versionNode.get("due_date");
				if (dueDateNode != null)
					milestone.setDueDate(ISODateTimeFormat.date().parseDateTime(dueDateNode.asText()).toDate());
				if (versionNode.get("status").asText().equals("closed"))
					milestone.setClosed(true);

				String wikiPageId = versionNode.get("name").asText().replace(' ', '_').replace(".", "");
				apiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/wiki/" + wikiPageId +".json");
				try {
					JsonNode wikiPageNode = JerseyUtils.get(client, apiEndpoint, logger).get("wiki_page");
					String wikiText = wikiPageNode.get("text").asText();
					if (wikiText != null) {
						String description = milestone.getDescription();
						milestone.setDescription(description != null ? description + "\n\n" + wikiText : wikiText);
					}
				} catch (ExplicitException ex) {
					// no associated wiki page
				}

				milestones.add(milestone);
				oneDevProject.getMilestones().add(milestone);

				if (!dryRun)
					OneDev.getInstance(MilestoneManager.class).save(milestone);
			}
		} finally {
			client.close();
		}
	}

	private static void importIssueCategories(ImportServer server, String redmineProject,
			IssueImportOption importOption, boolean dryRun, TaskLogger logger) {
		Client client = server.newClient();
		try {
			String redmineProjectId = getRedmineProjectId(redmineProject);
			String categoryIssueField = importOption.getCategoryIssueField();

			GlobalIssueSetting issueSetting = getIssueSetting();
			for (FieldSpec field : issueSetting.getFieldSpecs()) {
				if (field.getName().equals(categoryIssueField)) {
					logger.log("Issue Category '" + categoryIssueField + "' already exists");
					return;
				}
			}

			List<Choice> choices = new ArrayList<>();
			logger.log("Importing issue categories from project " + redmineProject + "...");
			String apiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/issue_categories.json");
			for (JsonNode categoryNode: list(client, apiEndpoint, "issue_categories", logger)) {
				String name = categoryNode.get("name").asText();

				Choice choice = new Choice();
				choice.setValue(name);
				choices.add(choice);
			}

			SpecifiedChoices specifiedChoices = new SpecifiedChoices();
			specifiedChoices.setChoices(choices);

			ChoiceField field = new ChoiceField();
			field.setName(categoryIssueField);
			field.setNameOfEmptyValue("Undefined");
			field.setAllowEmpty(true);
			field.setChoiceProvider(specifiedChoices);

			if (!dryRun) {
				issueSetting.getFieldSpecs().add(field);
				OneDev.getInstance(SettingManager.class).saveIssueSetting(issueSetting);
			}
		} finally {
			client.close();
		}
	}

	static GlobalIssueSetting getIssueSetting() {
		return OneDev.getInstance(SettingManager.class).getIssueSetting();
	}

	static List<JsonNode> list(Client client, String apiEndpoint, String dataNodeName, TaskLogger logger) {
		List<JsonNode> result = new ArrayList<>();
		list(client, apiEndpoint, dataNodeName, new PageDataConsumer() {

			@Override
			public void consume(List<JsonNode> pageData) {
				result.addAll(pageData);
			}

		}, logger);
		return result;
	}

	static void list(Client client, String apiEndpoint, String dataNodeName, PageDataConsumer pageDataConsumer,
			TaskLogger logger) {
		URI uri;
		try {
			uri = new URIBuilder(apiEndpoint).build();
		} catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}

		int offset = 0;
		while (true) {
			try {
				URIBuilder builder = new URIBuilder(uri);
				if (offset > 0)
					builder.addParameter("offset", String.valueOf(offset));
				List<JsonNode> pageData = new ArrayList<>();
				JsonNode resultNode = JerseyUtils.get(client, builder.build().toString(), logger);
				JsonNode dataNode = resultNode.get(dataNodeName);
				for (JsonNode each: dataNode)
					pageData.add(each);
				pageDataConsumer.consume(pageData);
				JsonNode totalCountNode = resultNode.get("total_count");
				if (totalCountNode == null)
					break;
				int totalCount = totalCountNode.asInt();
				if (offset + pageData.size() <= totalCount)
					break;
				offset += pageData.size();
			} catch (URISyntaxException|InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

	static String getRedmineProjectId(String redmineProject) {
		int sep = redmineProject.lastIndexOf(':');
		return redmineProject.substring(sep + 1);
	}
}