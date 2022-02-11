package com.devcharly.onedev.plugin.imports.redmine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.commons.lang.StringUtils;
import org.apache.http.client.utils.URIBuilder;
import org.joda.time.format.ISODateTimeFormat;
import org.unbescape.html.HtmlEscape;

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
import io.onedev.server.model.IssueChange;
import io.onedev.server.model.IssueComment;
import io.onedev.server.model.IssueField;
import io.onedev.server.model.IssueSchedule;
import io.onedev.server.model.IssueWatch;
import io.onedev.server.model.Milestone;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.model.support.LastUpdate;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.inputspec.InputSpec;
import io.onedev.server.model.support.inputspec.choiceinput.choiceprovider.Choice;
import io.onedev.server.model.support.inputspec.choiceinput.choiceprovider.SpecifiedChoices;
import io.onedev.server.model.support.issue.changedata.IssueChangeData;
import io.onedev.server.model.support.issue.changedata.IssueFieldChangeData;
import io.onedev.server.model.support.issue.changedata.IssueMilestoneAddData;
import io.onedev.server.model.support.issue.changedata.IssueMilestoneChangeData;
import io.onedev.server.model.support.issue.changedata.IssueMilestoneRemoveData;
import io.onedev.server.model.support.issue.changedata.IssueStateChangeData;
import io.onedev.server.model.support.issue.changedata.IssueTitleChangeData;
import io.onedev.server.model.support.issue.field.spec.ChoiceField;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.util.Input;
import io.onedev.server.util.JerseyUtils;
import io.onedev.server.util.JerseyUtils.PageDataConsumer;
import io.onedev.server.util.Pair;

public class ImportUtils {

	static final String NAME = "Redmine";

	static final int PER_PAGE = 50;

	private static final Map<String, String> statusDefaultFields = new HashMap<>();
	private static final Map<String, String> trackerDefaultFields = new HashMap<>();
	private static final Map<String, String> priorityDefaultFields = new HashMap<>();

	static {
		statusDefaultFields.put("New", "Open");
		statusDefaultFields.put("In Progress", "Open");
		statusDefaultFields.put("Resolved", "Open");
		statusDefaultFields.put("Feedback", "Open");
		statusDefaultFields.put("Closed", "Closed");
		statusDefaultFields.put("Rejected", "Closed");

		trackerDefaultFields.put("Bug", "Type::Bug");
		trackerDefaultFields.put("Feature", "Type::New Feature");
		trackerDefaultFields.put("Task", "Type::Task");

		priorityDefaultFields.put("Low", "Priority::Minor");
		priorityDefaultFields.put("Normal", "Priority::Normal");
		priorityDefaultFields.put("High", "Priority::Major");
		priorityDefaultFields.put("Urgent", "Priority::Critical");
		priorityDefaultFields.put("Immediate", "Priority::Critical");
	}

	static IssueImportOption buildImportOption(ImportServer server, Collection<String> redmineProjects, TaskLogger logger) {
		IssueImportOption importOption = new IssueImportOption();
		Client client = server.newClient();
		try {
			Set<String> statuses = new LinkedHashSet<>();
			String statusesApiEndpoint = server.getApiEndpoint("/issue_statuses.json");
			for (JsonNode trackerNode: list(client, statusesApiEndpoint, "issue_statuses", logger))
				statuses.add(trackerNode.get("name").asText());

			Set<String> trackers = new LinkedHashSet<>();
			String trackersApiEndpoint = server.getApiEndpoint("/trackers.json");
			for (JsonNode trackerNode: list(client, trackersApiEndpoint, "trackers", logger))
				trackers.add(trackerNode.get("name").asText());

			Set<String> priorities = new LinkedHashSet<>();
			String prioritiesApiEndpoint = server.getApiEndpoint("/enumerations/issue_priorities.json");
			for (JsonNode priorityNode: list(client, prioritiesApiEndpoint, "issue_priorities", logger))
				priorities.add(priorityNode.get("name").asText());

			Set<String> customFields = new LinkedHashSet<>();
			String customFieldsApiEndpoint = server.getApiEndpoint("/custom_fields.json");
			for (JsonNode priorityNode: list(client, customFieldsApiEndpoint, "custom_fields", logger)) {
				if ("issue".equals(priorityNode.get("customized_type").asText()))
					customFields.add(priorityNode.get("name").asText());
			}

			List<String> stateChoices = IssueStatusMapping.getOneDevIssueStateChoices();
			for (String status: statuses) {
				String defaultState = stateChoices.contains(status)
						? status
						: statusDefaultFields.get(status);
				IssueStatusMapping mapping = new IssueStatusMapping();
				mapping.setRedmineIssueStatus(status);
				mapping.setOneDevIssueState(defaultState);
				importOption.getIssueStatusMappings().add(mapping);
			}

			List<String> trackerFieldChoices = IssueTrackerMapping.getOneDevIssueFieldChoices();
			for (String tracker: trackers) {
				String defaultField = trackerFieldChoices.contains("Type::" + tracker)
						? "Type::" + tracker
						: trackerDefaultFields.get(tracker);
				IssueTrackerMapping mapping = new IssueTrackerMapping();
				mapping.setRedmineIssueTracker(tracker);
				mapping.setOneDevIssueField(defaultField);
				importOption.getIssueTrackerMappings().add(mapping);
			}

			List<String> priorityFieldChoices = IssuePriorityMapping.getOneDevIssueFieldChoices();
			for (String priority: priorities) {
				String defaultField = priorityFieldChoices.contains("Priority::" + priority)
						? "Priority::" + priority
						: priorityDefaultFields.get(priority);
				IssuePriorityMapping mapping = new IssuePriorityMapping();
				mapping.setRedmineIssuePriority(priority);
				mapping.setOneDevIssueField(defaultField);
				importOption.getIssuePriorityMappings().add(mapping);
			}

			List<String> customFieldFieldChoices = IssueFieldMapping.getOneDevIssueFieldChoices();
			for (String customField: customFields) {
				String defaultField = customFieldFieldChoices.contains(customField)
						? customField
						: null;
				IssueFieldMapping mapping = new IssueFieldMapping();
				mapping.setRedmineIssueField(customField);
				mapping.setOneDevIssueField(defaultField);
				importOption.getIssueFieldMappings().add(mapping);
			}
		} finally {
			client.close();
		}
		return importOption;
	}

	@Nullable
	static User getUser(Client client, ImportServer importSource,
			Map<String, Optional<User>> users, String login, TaskLogger logger) {
		Optional<User> userOpt = users.get(login);
		if (userOpt == null) {
			String apiEndpoint = importSource.getApiEndpoint("/users/" + login + ".json");
			try {
				String email = JerseyUtils.get(client, apiEndpoint, logger).get("user").get("mail").asText(null);
				if (email != null)
					userOpt = Optional.ofNullable(OneDev.getInstance(UserManager.class).findByEmail(email));
				else
					userOpt = Optional.empty();
			} catch (ExplicitException|NullPointerException ex) {
				// Redmine returns status 404 for unknown users
				userOpt = Optional.empty();
			}
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
			Set<String> unmappedIssueTypes = new HashSet<>();
			Set<String> unmappedIssuePriorities = new HashSet<>();
			Set<String> unmappedIssueFields = new HashSet<>();
			Set<String> tooLargeAttachments = new LinkedHashSet<>();
			Set<String> resultNotes = new LinkedHashSet<>();

			Map<String, String> statusMappings = new HashMap<>();
			Map<String, Pair<FieldSpec, String>> trackerMappings = new HashMap<>();
			Map<String, Pair<FieldSpec, String>> priorityMappings = new HashMap<>();
			Map<String, FieldSpec> fieldMappings = new HashMap<>();

			Map<String, Milestone> milestoneMappings = new HashMap<>();

			Map<String, String> userId2nameMap = new HashMap<>();
			Map<String, String> versionId2nameMap = new HashMap<>();
			Map<String, String> statusId2nameMap = new HashMap<>();
			Map<String, String> trackerId2nameMap = new HashMap<>();
			Map<String, String> priorityId2nameMap = new HashMap<>();
			Map<String, String> categoryId2nameMap = new HashMap<>();
			Map<String, String> fieldId2nameMap = new HashMap<>();

			GlobalIssueSetting issueSetting = getIssueSetting();

			for (IssueStatusMapping mapping: importOption.getIssueStatusMappings())
				statusMappings.put(mapping.getRedmineIssueStatus(), mapping.getOneDevIssueState());

			for (IssueTrackerMapping mapping: importOption.getIssueTrackerMappings()) {
				String oneDevFieldName = StringUtils.substringBefore(mapping.getOneDevIssueField(), "::");
				String oneDevFieldValue = StringUtils.substringAfter(mapping.getOneDevIssueField(), "::");
				FieldSpec fieldSpec = issueSetting.getFieldSpec(oneDevFieldName);
				if (fieldSpec == null)
					throw new ExplicitException("No field spec found: " + oneDevFieldName);
				trackerMappings.put(mapping.getRedmineIssueTracker(), new Pair<>(fieldSpec, oneDevFieldValue));
			}

			for (IssuePriorityMapping mapping: importOption.getIssuePriorityMappings()) {
				String oneDevFieldName = StringUtils.substringBefore(mapping.getOneDevIssueField(), "::");
				String oneDevFieldValue = StringUtils.substringAfter(mapping.getOneDevIssueField(), "::");
				FieldSpec fieldSpec = issueSetting.getFieldSpec(oneDevFieldName);
				if (fieldSpec == null)
					throw new ExplicitException("No field spec found: " + oneDevFieldName);
				priorityMappings.put(mapping.getRedmineIssuePriority(), new Pair<>(fieldSpec, oneDevFieldValue));
			}

			for (IssueFieldMapping mapping: importOption.getIssueFieldMappings()) {
				String oneDevFieldName = mapping.getOneDevIssueField();
				FieldSpec fieldSpec = issueSetting.getFieldSpec(oneDevFieldName);
				if (fieldSpec == null)
					throw new ExplicitException("No field spec found: " + oneDevFieldName);
				fieldMappings.put(mapping.getRedmineIssueField(), fieldSpec);
			}

			for (Milestone milestone: oneDevProject.getMilestones())
				milestoneMappings.put(milestone.getName(), milestone);

			String usersApiEndpoint = server.getApiEndpoint("/users.json");
			for (JsonNode userNode: list(client, usersApiEndpoint, "users", logger))
				userId2nameMap.put(userNode.get("id").asText(), userNode.get("firstname").asText() + " " + userNode.get("lastname").asText());

			String versionsApiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/versions.json");
			for (JsonNode versionNode: list(client, versionsApiEndpoint, "versions", logger))
				versionId2nameMap.put(versionNode.get("id").asText(), versionNode.get("name").asText());

			String statusesApiEndpoint = server.getApiEndpoint("/issue_statuses.json");
			for (JsonNode statusNode: list(client, statusesApiEndpoint, "issue_statuses", logger))
				statusId2nameMap.put(statusNode.get("id").asText(), statusNode.get("name").asText());

			String trackersApiEndpoint = server.getApiEndpoint("/trackers.json");
			for (JsonNode trackerNode: list(client, trackersApiEndpoint, "trackers", logger))
				trackerId2nameMap.put(trackerNode.get("id").asText(), trackerNode.get("name").asText());

			String prioritiesApiEndpoint = server.getApiEndpoint("/enumerations/issue_priorities.json");
			for (JsonNode priorityNode: list(client, prioritiesApiEndpoint, "issue_priorities", logger))
				priorityId2nameMap.put(priorityNode.get("id").asText(), priorityNode.get("name").asText());

			String categoriesEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/issue_categories.json");
			for (JsonNode categoryNode: list(client, categoriesEndpoint, "issue_categories", logger))
				categoryId2nameMap.put(categoryNode.get("id").asText(), categoryNode.get("name").asText());

			String customFieldsApiEndpoint = server.getApiEndpoint("/custom_fields.json");
			for (JsonNode customFieldNode: list(client, customFieldsApiEndpoint, "custom_fields", logger))
				fieldId2nameMap.put(customFieldNode.get("id").asText(), customFieldNode.get("name").asText());

			importIssueCategories(server, redmineProject, importOption, dryRun, logger);

			String initialIssueState = issueSetting.getInitialStateSpec().getName();

			List<Issue> issues = new ArrayList<>();

			Map<Long, Long> issueNumberMappings = new HashMap<>();

			AtomicInteger numOfImportedIssues = new AtomicInteger(0);
			PageDataConsumer pageDataConsumer = new PageDataConsumer() {

				@Nullable
				private String processAttachments(String issueUUID, String readableIssueId, @Nullable String markdown,
						List<JsonNode> attachmentNodes, Set<String> tooLargeAttachments) {
					if (markdown == null)
						markdown = "";

					String attachmentsLinks = "";

					long maxUploadFileSize = OneDev.getInstance(SettingManager.class)
							.getPerformanceSetting().getMaxUploadFileSize()*1L*1024*1024;
					for (JsonNode attachmentNode: attachmentNodes) {
						String attachmentName = attachmentNode.get("filename").asText(null);
						String attachmentUrl = attachmentNode.get("content_url").asText(null);
						long attachmentSize = attachmentNode.get("filesize").asLong(0);
						if (attachmentSize != 0 && attachmentName != null && attachmentUrl != null) {
							if (attachmentSize >  maxUploadFileSize) {
								tooLargeAttachments.add(readableIssueId + ":" + attachmentName);
							} else {
								String endpoint = attachmentUrl;
								WebTarget target = client.target(endpoint);
								Invocation.Builder builder =  target.request();
								try (Response response = builder.get()) {
									String errorMessage = JerseyUtils.checkStatus(endpoint, response);
									if (errorMessage != null) {
										throw new ExplicitException(String.format(
												"Error downloading attachment (url: %s, error message: %s)",
												endpoint, errorMessage));
									}
									try (InputStream is = response.readEntity(InputStream.class)) {
										String oneDevAttachmentName = oneDevProject.saveAttachment(issueUUID, attachmentName, is);
										String oneDevAttachmentUrl = oneDevProject.getAttachmentUrlPath(issueUUID, oneDevAttachmentName);
										if (markdown.contains("(" + attachmentName + ")")) {
											markdown = markdown.replace("(" + attachmentName + ")", "(" + oneDevAttachmentUrl + ")");
										}

										String description = attachmentNode.get("description").asText();
										attachmentsLinks += "[" + attachmentName + "](" + oneDevAttachmentUrl + ")"
												+ (!description.isEmpty() ? " - " + description : "")
												+ " (" + attachmentNode.get("author").get("name").asText()
												+ ", " + attachmentNode.get("created_on").asText() + ")\n";
									} catch (IOException e) {
										throw new RuntimeException(e);
									}
								}

							}
						}
					}

					if (!attachmentsLinks.isEmpty())
						markdown += "\n\n**Attachments:**\n" + attachmentsLinks;

					if (markdown.length() == 0)
						markdown = null;

					return markdown;
				}

				private String joinAsMultilineHtml(List<String> values) {
					List<String> escapedValues = new ArrayList<>();
					for (String value: values)
						escapedValues.add(HtmlEscape.escapeHtml5(value));
					return StringUtils.join(escapedValues, "<br>");
				}

				@Override
				public void consume(List<JsonNode> pageData) throws InterruptedException {
					for (JsonNode issueNode: pageData) {
						if (Thread.interrupted())
							throw new InterruptedException();

						Map<String, String> extraIssueInfo = new LinkedHashMap<>();

						Issue issue = new Issue();
						issue.setProject(oneDevProject);
						issue.setNumberScope(oneDevProject.getForkRoot());

						LastUpdate lastUpdate = new LastUpdate();
						lastUpdate.setActivity("opened");
						lastUpdate.setDate(issue.getSubmitDate());
						lastUpdate.setUser(issue.getSubmitter());

						// initialize all custom fields
						for (FieldSpec fieldSpec : issueSetting.getFieldSpecs())
							issue.setFieldValue(fieldSpec.getName(), null);

						// subject --> title
						issue.setTitle(issueNode.get("subject").asText());

						// description --> description
						issue.setDescription(issueNode.get("description").asText(null));

						// issue id --> number
						Long oldNumber = issueNode.get("id").asLong();
						Long newNumber;
						if (dryRun || useExistingIssueNumbers)
							newNumber = oldNumber;
						else
							newNumber = OneDev.getInstance(IssueManager.class).getNextNumber(oneDevProject);
						issue.setNumber(newNumber);
						issueNumberMappings.put(oldNumber, newNumber);

						// status --> state
						String status = issueNode.get("status").get("name").asText();
						String state = statusMappings.getOrDefault(status, initialIssueState);
						issue.setState(state);

						// fixed_version ("Target version") --> milestone
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

						// author --> submitter
						String login = issueNode.get("author").get("id").asText(null);
						User user = getUser(client, server, users, login, logger);
						if (user != null) {
							issue.setSubmitter(user);
						} else {
							issue.setSubmitter(OneDev.getInstance(UserManager.class).getUnknown());
							nonExistentLogins.add(issueNode.get("author").get("name").asText() + ":" + login);
						}

						// created_on --> submit date
						issue.setSubmitDate(ISODateTimeFormat.dateTimeNoMillis()
								.parseDateTime(issueNode.get("created_on").asText())
								.toDate());

						// tracker --> custom field "Type"
						JsonNode trackerNode = issueNode.get("tracker");
						if (trackerNode != null) {
							String trackerName = trackerNode.get("name").asText();
							Pair<FieldSpec, String> mapped = trackerMappings.get(trackerName);
							if (mapped != null) {
								issue.setFieldValue(mapped.getFirst().getName(), mapped.getSecond());
							} else {
								extraIssueInfo.put("Type", HtmlEscape.escapeHtml5(trackerName));
								unmappedIssueTypes.add(HtmlEscape.escapeHtml5(trackerName));
							}
						}

						// priority --> custom field "Priority"
						JsonNode priorityNode = issueNode.get("priority");
						if (priorityNode != null) {
							String priorityName = priorityNode.get("name").asText();
							Pair<FieldSpec, String> mapped = priorityMappings.get(priorityName);
							if (mapped != null) {
								issue.setFieldValue(mapped.getFirst().getName(), mapped.getSecond());
							} else {
								extraIssueInfo.put("Priority", HtmlEscape.escapeHtml5(priorityName));
								unmappedIssuePriorities.add(priorityName);
							}
						}

						// assigned_to --> custom field "Assignees"
						JsonNode assigneeNode = issueNode.get("assigned_to");
						if (assigneeNode != null) {
							login = assigneeNode.get("id").asText();
							user = getUser(client, server, users, login, logger);
							if (user != null) {
								issue.setFieldValue(importOption.getAssigneesIssueField(), user.getName());
							} else {
								nonExistentLogins.add(assigneeNode.get("name").asText() + ":" + login);
							}
						}

						// category --> custom field "Category"
						JsonNode categoryNode = issueNode.get("category");
						String categoryValue = (categoryNode != null) ? categoryNode.get("name").asText() : null;
						issue.setFieldValue(importOption.getCategoryIssueField(), categoryValue);

						// custom_fields
						JsonNode customFieldsNode = issueNode.get("custom_fields");
						if (customFieldsNode != null) {
							for (JsonNode customFieldNode : customFieldsNode) {
								String fieldName = customFieldNode.get("name").asText();
								JsonNode valueNode = customFieldNode.get("value");
								if (valueNode == null)
									continue;

								Object value;
								if (valueNode.isArray()) {
									List<String> values = new ArrayList<>();
									for (JsonNode node : valueNode)
										values.add(node.asText());
									if (values.isEmpty())
										continue;
									value = values;
								} else {
									value = valueNode.asText();
									if (((String)value).isEmpty())
										continue;
								}

								FieldSpec mapped = fieldMappings.get(fieldName);
								if (mapped != null) {
									issue.setFieldValue(fieldName, value);
								} else {
									@SuppressWarnings("unchecked")
									String v = (value instanceof List)
										? joinAsMultilineHtml((List<String>)value)
										: HtmlEscape.escapeHtml5((String) value);
									extraIssueInfo.put(fieldName, v);
									unmappedIssueFields.add(fieldName);
								}
							}
						}

						// get additional issue information
						String apiEndpoint = server.getApiEndpoint("/issues/" + oldNumber + ".json?include=watchers,attachments,journals");
						JsonNode issueNode2 = JerseyUtils.get(client, apiEndpoint, logger).get("issue");

						// watchers --> watches
						JsonNode watchersNode = issueNode2.get("watchers");
						if (watchersNode != null) {
							for (JsonNode watcherNode: watchersNode) {
								login = watcherNode.get("id").asText();
								user = getUser(client, server, users, login, logger);
								if (user != null) {
									IssueWatch watch = new IssueWatch();
									watch.setIssue(issue);
									watch.setUser(user);
									watch.setWatching(true);
									issue.getWatches().add(watch);
								} else {
									user = OneDev.getInstance(UserManager.class).getUnknown();
									nonExistentLogins.add(watcherNode.get("name").asText() + ":" + login);
								}
							}
						}

						// attachments
						JsonNode attachmentsNode = issueNode2.get("attachments");
						if (!dryRun && attachmentsNode != null) {
							List<JsonNode> attachmentNodes = new ArrayList<>();
							for (JsonNode attachmentNode: attachmentsNode)
								attachmentNodes.add(attachmentNode);
							if (!attachmentNodes.isEmpty()) {
								issue.setDescription(processAttachments(issue.getUUID(), "#" + oldNumber,
										issue.getDescription(), attachmentNodes, tooLargeAttachments));
							}
						}

						// journals ("History") --> comments, changes
						JsonNode journalsNode = issueNode2.get("journals");
						for (JsonNode journalNode: journalsNode) {
							login = journalNode.get("user").get("id").asText();
							user = getUser(client, server, users, login, logger);
							if (user == null) {
								user = OneDev.getInstance(UserManager.class).getUnknown();
								nonExistentLogins.add(journalNode.get("user").get("name").asText() + ":" + login);
							}

							Date createdOn = ISODateTimeFormat.dateTimeNoMillis()
									.parseDateTime(journalNode.get("created_on").asText())
									.toDate();

							JsonNode notesNode = journalNode.get("notes");
							String notes = (notesNode != null) ? notesNode.asText() : "";
							if (!notes.isEmpty()) {
								IssueComment comment = new IssueComment();
								comment.setIssue(issue);
								comment.setContent(notes);
								comment.setUser(user);
								comment.setDate(createdOn);

								issue.getComments().add(comment);
								issue.setCommentCount(issue.getComments().size());

								lastUpdate.setActivity("commented");
								lastUpdate.setDate(comment.getDate());
								lastUpdate.setUser(comment.getUser());
							}

							JsonNode detailsNode = journalNode.get("details");
							if (detailsNode != null) {
								Map<String, Input> oldFields = new LinkedHashMap<>();
								Map<String, Input> newFields = new LinkedHashMap<>();

								for (JsonNode detailNode : detailsNode) {
									String property = detailNode.get("property").asText();
									String name = detailNode.get("name").asText();
									JsonNode oldValueNode = detailNode.get("old_value");
									JsonNode newValueNode = detailNode.get("new_value");
									String oldValue = (oldValueNode != null) ? oldValueNode.asText(null) : null;
									String newValue = (newValueNode != null) ? newValueNode.asText(null) : null;
									IssueChangeData data = null;

									if ("attr".equals(property)) {
										if ("subject".equals(name)) {
											data = new IssueTitleChangeData(oldValue, newValue);
										} else if ("description".equals(name)) {
											// not migrated because OneDev does not support description history
										} else if ("status_id".equals(name)) {
											// do not convert Redmine status to OneDev state for change history
											String oldStatus = statusId2nameMap.get(oldValue);
											String newStatus = statusId2nameMap.get(newValue);
											data = new IssueStateChangeData(oldStatus, newStatus, Collections.emptyMap(), Collections.emptyMap());
										} else if ("tracker_id".equals(name)) {
											// do not convert Redmine tracker to OneDev type for change history
											String oldTracker = trackerId2nameMap.get(oldValue);
											String newTracker = trackerId2nameMap.get(newValue);
											addToFields("Type", oldTracker, oldFields);
											addToFields("Type", newTracker, newFields);
										} else if ("priority_id".equals(name)) {
											// do not convert Redmine priority to OneDev priority for change history
											String oldPriority = priorityId2nameMap.get(oldValue);
											String newPriority = priorityId2nameMap.get(newValue);
											addToFields("Priority", oldPriority, oldFields);
											addToFields("Priority", newPriority, newFields);
										} else if ("assigned_to_id".equals(name)) {
											if (oldValue != null) {
												User oldUser = getUser(client, server, users, oldValue, logger);
												if (oldUser != null)
													addToFields(importOption.getAssigneesIssueField(), oldUser.getName(), oldFields);
												else
													nonExistentLogins.add(userId2nameMap.getOrDefault(oldValue, "") + ":" + oldValue);
											}
											if (newValue != null) {
												User newUser = getUser(client, server, users, newValue, logger);
												if (newUser != null)
													addToFields(importOption.getAssigneesIssueField(), newUser.getName(), newFields);
												else
													nonExistentLogins.add(userId2nameMap.getOrDefault(newValue, "") + ":" + newValue);
											}
										} else if ("category_id".equals(name)) {
											String oldCategory = categoryId2nameMap.get(oldValue);
											String newCategory = categoryId2nameMap.get(newValue);
											addToFields(importOption.getCategoryIssueField(), oldCategory, oldFields);
											addToFields(importOption.getCategoryIssueField(), newCategory, newFields);
										} else if ("fixed_version_id".equals(name)) {
											String oldVersion = versionId2nameMap.get(oldValue);
											String newVersion = versionId2nameMap.get(newValue);
											if (oldVersion != null && newVersion != null) {
												Milestone oldMilestone = new Milestone();
												Milestone newMilestone = new Milestone();
												oldMilestone.setName(oldVersion);
												newMilestone.setName(newVersion);
												data = new IssueMilestoneChangeData(Collections.singletonList(oldMilestone), Collections.singletonList(newMilestone));
											} else if (newVersion != null) {
												data = new IssueMilestoneAddData(newVersion);
											} else if (oldVersion != null) {
												data = new IssueMilestoneRemoveData(oldVersion);
											}
										} else {
											resultNotes.add(String.format(
												"Unknown history property name '%s' in Redmine issue <a href=\"%s\">#%d</a> (<a href=\"%s\">JSON</a>)",
												HtmlEscape.escapeHtml5(name), server.getApiEndpoint("/issues/" + oldNumber), oldNumber, apiEndpoint));
										}
									} else if ("cf".equals(property)) {
										// custom fields
										String fieldName = fieldId2nameMap.get(name);
										if (fieldName != null) {
											addToFields(fieldName, oldValue, oldFields);
											addToFields(fieldName, newValue, newFields);
										} else {
											resultNotes.add(String.format(
												"Unknown history custom field '%s' in Redmine issue <a href=\"%s\">#%d</a> (<a href=\"%s\">JSON</a>)",
												HtmlEscape.escapeHtml5(name), server.getApiEndpoint("/issues/" + oldNumber), oldNumber, apiEndpoint));
										}
									} else if ("attachment".equals(property)) {
										// not migrated because OneDev does not support attachment history
									} else {
										resultNotes.add(String.format(
											"Unknown history property '%s' in Redmine issue <a href=\"%s\">#%d</a> (<a href=\"%s\">JSON</a>)",
											HtmlEscape.escapeHtml5(property), server.getApiEndpoint("/issues/" + oldNumber), oldNumber, apiEndpoint));
									}

									if (data != null) {
										IssueChange issueChange = new IssueChange();
										issueChange.setIssue(issue);
										issueChange.setDate(createdOn);
										issueChange.setUser(user);
										issueChange.setData(data);

										issue.getChanges().add(issueChange);

										lastUpdate.setActivity(issueChange.getData().getActivity());
										lastUpdate.setDate(issueChange.getDate());
										lastUpdate.setUser(issueChange.getUser());
									}
								}

								if (!oldFields.isEmpty() || !newFields.isEmpty()) {
									IssueChange issueChange = new IssueChange();
									issueChange.setIssue(issue);
									issueChange.setDate(createdOn);
									issueChange.setUser(user);
									issueChange.setData(new IssueFieldChangeData(oldFields, newFields));

									issue.getChanges().add(issueChange);

									lastUpdate.setActivity(issueChange.getData().getActivity());
									lastUpdate.setDate(issueChange.getDate());
									lastUpdate.setUser(issueChange.getUser());
								}
							}
						}

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

						issue.setLastUpdate(lastUpdate);

						issues.add(issue);
					}

					logger.log("Imported " + numOfImportedIssues.addAndGet(pageData.size()) + " issues");
				}

			};

			logger.log("Importing issues from project " + redmineProject + "...");

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
					for (IssueChange change: issue.getChanges())
						dao.persist(change);
					for (IssueWatch change: issue.getWatches())
						dao.persist(change);
				}
			}

			ImportResult result = new ImportResult();
			result.nonExistentLogins.addAll(nonExistentLogins);
			result.nonExistentMilestones.addAll(nonExistentMilestones);
			result.unmappedIssueTypes.addAll(unmappedIssueTypes);
			result.unmappedIssuePriorities.addAll(unmappedIssuePriorities);
			result.unmappedIssueFields.addAll(unmappedIssueFields);
			result.tooLargeAttachments.addAll(tooLargeAttachments);
			result.notes.addAll(resultNotes);

			return result;
		} finally {
			client.close();
		}
	}

	private static void addToFields(String fieldName, String value, Map<String, Input> fields) {
		if (value != null && !value.isEmpty())
			fields.put(fieldName, new Input(fieldName, InputSpec.ENUMERATION, Collections.singletonList(value)));
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
				builder.addParameter("limit", String.valueOf(PER_PAGE));
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
				if (offset + pageData.size() >= totalCount)
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