package com.devcharly.onedev.plugin.imports.redmine;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.OneDev;
import io.onedev.server.attachment.AttachmentManager;
import io.onedev.server.buildspecmodel.inputspec.InputSpec;
import io.onedev.server.buildspecmodel.inputspec.choiceinput.choiceprovider.Choice;
import io.onedev.server.buildspecmodel.inputspec.choiceinput.choiceprovider.SpecifiedChoices;
import io.onedev.server.entitymanager.*;
import io.onedev.server.entityreference.ReferenceMigrator;
import io.onedev.server.model.*;
import io.onedev.server.model.support.LastActivity;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.issue.LinkSpecOpposite;
import io.onedev.server.model.support.issue.changedata.*;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;
import io.onedev.server.model.support.issue.field.spec.choicefield.ChoiceField;
import io.onedev.server.persistence.dao.Dao;
import io.onedev.server.util.Input;
import io.onedev.server.util.JerseyUtils;
import io.onedev.server.util.Pair;
import org.apache.commons.lang.StringUtils;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unbescape.html.HtmlEscape;

import javax.annotation.Nullable;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

public class ImportUtils {

	static final String NAME = "Redmine";

	static final int PER_PAGE = 50;

	private static final Logger sl4jLogger = LoggerFactory.getLogger(ImportUtils.class);


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

	private Group defaultGroup;

	private Set<String> usersCreated = new HashSet<>();

	static IssueImportOption buildImportOption(ImportServer server) {
		TaskLogger logger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				sl4jLogger.info(message);
			}

		};

		IssueImportOption importOption = new IssueImportOption();
		Client client = server.newClient();

		RedmineClient rc = new RedmineClient(client, logger);

		try {
			Set<String> statuses = new LinkedHashSet<>();
			String statusesApiEndpoint = server.getApiEndpoint("/issue_statuses.json");
			for (JsonNode trackerNode: rc.list(statusesApiEndpoint, "issue_statuses"))
				statuses.add(trackerNode.get("name").asText());

			Set<String> trackers = new LinkedHashSet<>();
			String trackersApiEndpoint = server.getApiEndpoint("/trackers.json");
			for (JsonNode trackerNode: rc.list(trackersApiEndpoint, "trackers"))
				trackers.add(trackerNode.get("name").asText());

			Set<String> priorities = new LinkedHashSet<>();
			String prioritiesApiEndpoint = server.getApiEndpoint("/enumerations/issue_priorities.json");
			for (JsonNode priorityNode: rc.list(prioritiesApiEndpoint, "issue_priorities"))
				priorities.add(priorityNode.get("name").asText());

			Set<String> customFields = new LinkedHashSet<>();
			String customFieldsApiEndpoint = server.getApiEndpoint("/custom_fields.json");
			for (JsonNode priorityNode: rc.list(customFieldsApiEndpoint, "custom_fields")) {
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

	private Map<String, Optional<User>> users = new HashMap<>();

	private ImportServer server;
	private final IssueImportSource source;
	private String redmineProjectId;
	private Project oneDevProject;
	private IssueImportOption importOption;
	private boolean dryRun;
	private TaskLogger logger;

	public ImportUtils(ImportServer server, IssueImportSource what, Project oneDevProject,
					   IssueImportOption importOption, boolean dryRun, TaskLogger logger) {
		this.server = server;
		this.source = what;
		this.oneDevProject = oneDevProject;
		this.importOption = importOption;
		this.dryRun = dryRun;
		this.logger = logger;

		this.redmineProjectId = this.source.getRedmineProjectId();

		String assignUsersToGroup = this.importOption.getAssignUsersToGroup();
		if(assignUsersToGroup != null) {
			defaultGroup = OneDev.getInstance(GroupManager.class).find(assignUsersToGroup);
		}

	}

	@Nullable
	User getUser(Client client, String redmineUserId) {
		Optional<User> userOpt = users.get(redmineUserId);
		if (userOpt == null) {
			String apiEndpoint = server.getApiEndpoint("/users/" + redmineUserId + ".json");
			try {
				JsonNode redmineUser = JerseyUtils.get(client, apiEndpoint, logger).get("user");
				String email = redmineUser.get("mail").asText(null);
				String login = redmineUser.get("login").asText(null);
				String lastname = redmineUser.get("lastname").asText(null);
				String firstname = redmineUser.get("firstname").asText(null);

				if (email != null) {
					UserManager um = OneDev.getInstance(UserManager.class);
					userOpt = Optional.ofNullable(um.findByVerifiedEmailAddress(email));

					if(!userOpt.isPresent() && this.importOption.isCreateUser()) {
						EmailAddressManager em = OneDev.getInstance(EmailAddressManager.class);

						User nu = new User();
						nu.setFullName(String.format("%s %s", firstname, lastname));
						nu.setName(login);
						if(this.importOption.isCreateAsExternal()) {
							nu.setPassword(User.EXTERNAL_MANAGED);
						}
						nu.setGuest(this.importOption.isCreateAsGuestUser());

						EmailAddress ae = new EmailAddress();
						ae.setValue(email);
						ae.setGit(true);
						ae.setPrimary(true);
						ae.setOwner(nu);
						ae.setVerificationCode(null);

						if (!dryRun) {
							um.create(nu);
							em.create(ae);
						}
						nu.getEmailAddresses().add(ae);
						this.usersCreated.add(login);
						userOpt = Optional.of(nu);
					}

					if(userOpt.isPresent() && defaultGroup != null) {
						MembershipManager mm = OneDev.getInstance(MembershipManager.class);
						User user = userOpt.get();
						long existingMembership = user.getMemberships().stream().filter(membership -> membership.getGroup().getId().equals(defaultGroup.getId())).count();
						if (existingMembership == 0) {
							Membership membership = new Membership();
							membership.setUser(user);
							membership.setGroup(defaultGroup);
							if(!dryRun) {
								mm.create(membership);
							}
						}
					}
				} else {
					userOpt = Optional.empty();
				}
			} catch (ExplicitException|NullPointerException ex) {
				// Redmine returns status 404 for unknown users
				userOpt = Optional.empty();
			}
			users.put(redmineUserId, userOpt);
		}
		return userOpt.orElse(null);
	}

	ImportResult importIssues() {
		Client client = server.newClient();

		try {

			RedmineClient rc = new RedmineClient(client, this.logger);

			Set<String> nonExistentMilestones = new HashSet<>();
			Set<String> nonExistentLogins = new HashSet<>();
			Set<String> unmappedIssueTypes = new HashSet<>();
			Set<String> unmappedIssuePriorities = new HashSet<>();
			Set<String> unmappedIssueFields = new HashSet<>();
			Set<String> tooLargeAttachments = new LinkedHashSet<>();
			Set<String> resultNotes = new LinkedHashSet<>();

			Map<String, String> statusMappings = new HashMap<>();
			Set<String> statusMappingsAsLabel = new HashSet<>();

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

			for (IssueStatusMapping mapping: importOption.getIssueStatusMappings()) {
				statusMappings.put(mapping.getRedmineIssueStatus(), mapping.getOneDevIssueState());
				if(mapping.isAddLabel()) {
					statusMappingsAsLabel.add(mapping.getRedmineIssueStatus());
				}

			}

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
			for (JsonNode userNode: rc.list(usersApiEndpoint, "users"))
				userId2nameMap.put(userNode.get("id").asText(), userNode.get("firstname").asText() + " " + userNode.get("lastname").asText());

			String versionsApiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/versions.json");
			for (JsonNode versionNode: rc.list(versionsApiEndpoint, "versions"))
				versionId2nameMap.put(versionNode.get("id").asText(), versionNode.get("name").asText());

			String statusesApiEndpoint = server.getApiEndpoint("/issue_statuses.json");
			for (JsonNode statusNode: rc.list(statusesApiEndpoint, "issue_statuses"))
				statusId2nameMap.put(statusNode.get("id").asText(), statusNode.get("name").asText());

			String trackersApiEndpoint = server.getApiEndpoint("/trackers.json");
			for (JsonNode trackerNode: rc.list(trackersApiEndpoint, "trackers"))
				trackerId2nameMap.put(trackerNode.get("id").asText(), trackerNode.get("name").asText());

			String prioritiesApiEndpoint = server.getApiEndpoint("/enumerations/issue_priorities.json");
			for (JsonNode priorityNode: rc.list(prioritiesApiEndpoint, "issue_priorities"))
				priorityId2nameMap.put(priorityNode.get("id").asText(), priorityNode.get("name").asText());

			String categoriesEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/issue_categories.json");
			for (JsonNode categoryNode: rc.list(categoriesEndpoint, "issue_categories"))
				categoryId2nameMap.put(categoryNode.get("id").asText(), categoryNode.get("name").asText());

			String customFieldsApiEndpoint = server.getApiEndpoint("/custom_fields.json");
			for (JsonNode customFieldNode: rc.list(customFieldsApiEndpoint, "custom_fields"))
				fieldId2nameMap.put(customFieldNode.get("id").asText(), customFieldNode.get("name").asText());

			importIssueCategories();

			String initialIssueState = issueSetting.getInitialStateSpec().getName();

			List<Issue> issues = new ArrayList<>();

			Map<Long, Long> issueNumberMappings = new HashMap<>();
			Map<Long, Issue> issuesMap = new HashMap<>();
			Map<Long, Long> redmineParents = new HashMap<>();
			Map<String, JsonNode> redmineRelations = new HashMap<>();

			AtomicInteger numOfImportedIssues = new AtomicInteger(0);
			MyPageDataConsumer pageDataConsumer = new MyPageDataConsumer() {

				private int total = 0;
				@Override
				public void setTotal(int total) {
					this.total = total;
				}
				@Override
				public int getTotal() {
					return total;
				}

				@Nullable
				private String processAttachments(String issueUUID, String readableIssueId, @Nullable String markdown,
						List<JsonNode> attachmentNodes, Set<String> tooLargeAttachments) {
					if (markdown == null)
						markdown = "";

					String attachmentsLinks = "";

					long maxUploadFileSize = (long) OneDev.getInstance(SettingManager.class).getPerformanceSetting().getMaxUploadFileSize() * 1024 * 1024;
					AttachmentManager am = OneDev.getInstance(AttachmentManager.class);

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
										String oneDevAttachmentName = am.saveAttachment(oneDevProject.getId(), issueUUID, attachmentName, is);
										String oneDevAttachmentUrl = oneDevProject.getAttachmentUrlPath(issueUUID, oneDevAttachmentName);
										if (markdown.contains("(" + attachmentName + ")")) {
											markdown = markdown.replace("(" + attachmentName + ")", "(" + oneDevAttachmentUrl + ")");
										}

										String description = attachmentNode.get("description") != null ? attachmentNode.get("description").asText() : "";
										attachmentsLinks += "[" + attachmentName + "](" + oneDevAttachmentUrl + ")"
												+ (!description.isEmpty() ? " - " + description : "")
												+ " (" + attachmentNode.get("author").get("name").asText()
												+ ", " + attachmentNode.get("created_on").asText() + ")\n";
									} catch (IOException e) {
										logger.error(attachmentNode.toPrettyString() , e);
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

				private String convertText(String str) {
					if (str == null || str.isEmpty())
						return str;

					// normalize line separator to NL
					str = str.replace("\r\n", "\n").replace("\r", "\n");

					// convert textile to markdown
					if (importOption.isConvertTextileToMarkdown())
						str = RedmineTextileConverter.convertTextileToMarkdown(str);

					return str;
				}

				private String findSimilarMilestone(String milestone, Map<String, Milestone> milestoneMappings) {
					if (milestoneMappings.containsKey(milestone))
						return milestone;

					// try without trailing ".0"
					String m = milestone;
					while (m.endsWith(".0")) {
						m = StringUtils.removeEnd(m, ".0");
						if (milestoneMappings.containsKey(m))
							return m;
					}

					// try with appended ".0"
					m = milestone;
					for (int i = 0; i < 3; i++) {
						m += ".0";
						if (milestoneMappings.containsKey(m))
							return m;
					}

					return milestone;
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

						// initialize all custom fields
						for (FieldSpec fieldSpec : issueSetting.getFieldSpecs())
							issue.setFieldValue(fieldSpec.getName(), null);

						// subject --> title
						issue.setTitle(issueNode.get("subject").asText());

						// description --> description
						issue.setDescription(convertText(issueNode.get("description").asText(null)));

						// issue id --> number
						Long oldNumber = issueNode.get("id").asLong();
						Long newNumber;
						if (importOption.isUseExistingIssueIDs() && OneDev.getInstance(IssueManager.class).find(oneDevProject, oldNumber) != null)
							throw new ExplicitException("An issue with ID " + oldNumber + " already exists.");
						if (dryRun || importOption.isUseExistingIssueIDs())
							newNumber = oldNumber;
						else
							newNumber = OneDev.getInstance(IssueManager.class).getNextNumber(oneDevProject);
						issue.setNumber(newNumber);
						issueNumberMappings.put(oldNumber, newNumber);

						// parent
						JsonNode parentNode = issueNode.get("parent");
						if (parentNode != null)
							redmineParents.put(oldNumber, parentNode.get("id").asLong());

						// status --> state
						String status = issueNode.get("status").get("name").asText();
						String state = statusMappings.getOrDefault(status, initialIssueState);
						issue.setState(state);

						if(statusMappingsAsLabel.contains(status)) {
//							OneDev.getInstance(LabelM)
						}

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
						String redmineUserId = issueNode.get("author").get("id").asText(null);
						User user = getUser(client, redmineUserId);
						if (user != null) {
							issue.setSubmitter(user);
						} else {
							issue.setSubmitter(OneDev.getInstance(UserManager.class).getUnknown());
							nonExistentLogins.add(issueNode.get("author").get("name").asText() + ":" + redmineUserId);
						}

						// created_on --> submit date
						issue.setSubmitDate(ISODateTimeFormat.dateTimeNoMillis()
								.parseDateTime(issueNode.get("created_on").asText())
								.toDate());

						LastActivity lastUpdate = new LastActivity();
						lastUpdate.setDescription("opened");
						lastUpdate.setDate(issue.getSubmitDate());
						lastUpdate.setUser(issue.getSubmitter());

						// tracker --> custom field "Type"
						JsonNode trackerNode = issueNode.get("tracker");
						if (trackerNode != null) {
							String trackerName = trackerNode.get("name").asText();
							Pair<FieldSpec, String> mapped = trackerMappings.get(trackerName);
							if (mapped != null) {
								issue.setFieldValue(mapped.getLeft().getName(), mapped.getRight());
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
								issue.setFieldValue(mapped.getLeft().getName(), mapped.getRight());
							} else {
								extraIssueInfo.put("Priority", HtmlEscape.escapeHtml5(priorityName));
								unmappedIssuePriorities.add(priorityName);
							}
						}

						// assigned_to --> custom field "Assignees"
						JsonNode assigneeNode = issueNode.get("assigned_to");
						if (assigneeNode != null) {
							redmineUserId = assigneeNode.get("id").asText();
							user = getUser(client, redmineUserId);
							if (user != null) {
								issue.setFieldValue(importOption.getAssigneesIssueField(), user.getName());
							} else {
								nonExistentLogins.add(assigneeNode.get("name").asText() + ":" + redmineUserId);
							}
						}

						// category --> custom field "Category"
						JsonNode categoryNode = issueNode.get("category");
						String categoryValue = (categoryNode != null) ? categoryNode.get("name").asText() : null;
						issue.setFieldValue(importOption.getCategoryIssueField(), categoryValue);

						// start_date --> custom field
						JsonNode startDateNode = issueNode.get("start_date");
						if (startDateNode != null) {
							String startDate = startDateNode.asText(null);
							if (importOption.getStartDateField() != null)
								issue.setFieldValue(importOption.getStartDateField(), startDate);
							else
								extraIssueInfo.put("Start date", HtmlEscape.escapeHtml5(startDate));
						}

						// due_date --> custom field
						JsonNode dueDateNode = issueNode.get("due_date");
						if (dueDateNode != null) {
							String dueDate = dueDateNode.asText(null);
							if (importOption.getDueDateField() != null)
								issue.setFieldValue(importOption.getDueDateField(), dueDate);
							else
								extraIssueInfo.put("Due date", HtmlEscape.escapeHtml5(dueDate));
						}

						// done_ratio --> custom field
						JsonNode doneRatioNode = issueNode.get("done_ratio");
						if (doneRatioNode != null) {
							int doneRatio = doneRatioNode.asInt(-1);
							if (importOption.getDoneRatioField() != null)
								issue.setFieldValue(importOption.getDoneRatioField(), doneRatio);
							else
								extraIssueInfo.put("% Done", HtmlEscape.escapeHtml5(doneRatio + "%"));
						}

						// estimated_hours --> custom field
						JsonNode estimatedHoursNode = issueNode.get("estimated_hours");
						if (estimatedHoursNode != null) {
							int estimatedHours = estimatedHoursNode.asInt(-1);
							if (importOption.getEstimatedHoursField() != null)
								issue.setFieldValue(importOption.getEstimatedHoursField(), estimatedHours);
							else
								extraIssueInfo.put("Estimated time", HtmlEscape.escapeHtml5(estimatedHoursNode.asText(null)));
						}

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
									if (mapped.getType().equals(InputSpec.MILESTONE) && value instanceof String)
										value = findSimilarMilestone((String) value, milestoneMappings);

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
						String apiEndpoint = server.getApiEndpoint("/issues/" + oldNumber + ".json?include=relations,watchers,attachments,journals");
						JsonNode issueNode2 = JerseyUtils.get(client, apiEndpoint, logger).get("issue");

						// relations --> links
						JsonNode relationsNode = issueNode2.get("relations");
						if (relationsNode != null) {
							// since Redmine returns relation information in both issues,
							// put it into a map using relation ID as key to eliminate duplicates
							for (JsonNode relationNode: relationsNode)
								redmineRelations.put(relationNode.get("id").asText(), relationNode);
						}

						// watchers --> watches
						JsonNode watchersNode = issueNode2.get("watchers");
						if (watchersNode != null) {
							for (JsonNode watcherNode: watchersNode) {
								redmineUserId = watcherNode.get("id").asText();
								user = getUser(client, redmineUserId);
								if (user != null) {
									IssueWatch watch = new IssueWatch();
									watch.setIssue(issue);
									watch.setUser(user);
									watch.setWatching(true);
									issue.getWatches().add(watch);
								} else {
									user = OneDev.getInstance(UserManager.class).getUnknown();
									nonExistentLogins.add(watcherNode.get("name").asText() + ":" + redmineUserId);
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
							redmineUserId = journalNode.get("user").get("id").asText();
							user = getUser(client, redmineUserId);
							if (user == null) {
								user = OneDev.getInstance(UserManager.class).getUnknown();
								nonExistentLogins.add(journalNode.get("user").get("name").asText() + ":" + redmineUserId);
							}

							Date createdOn = ISODateTimeFormat.dateTimeNoMillis()
									.parseDateTime(journalNode.get("created_on").asText())
									.toDate();

							IssueComment comment = null;
							JsonNode notesNode = journalNode.get("notes");
							String notes = convertText((notesNode != null) ? notesNode.asText() : "");
							if (!notes.isEmpty()) {
								comment = new IssueComment();
								comment.setIssue(issue);
								comment.setContent(notes);
								comment.setUser(user);
								comment.setDate(createdOn);

								issue.getComments().add(comment);
								issue.setCommentCount(issue.getCommentCount() + 1);

								lastUpdate.setDescription("commented");
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
										} else if ("author_id".equals(name)) {
											String fieldName = "Author ID";
											addToFields(fieldName, oldValue, oldFields);
											addToFields(fieldName, newValue, newFields);
										} else if ("parent_id".equals(name)) {
											String fieldName = "Parent ID";
											addToFields(fieldName, oldValue, oldFields);
											addToFields(fieldName, newValue, newFields);
										}else if ("tracker_id".equals(name)) {
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
												User oldUser = getUser(client, oldValue);
												if (oldUser != null)
													addToFields(importOption.getAssigneesIssueField(), oldUser.getName(), oldFields);
												else
													nonExistentLogins.add(userId2nameMap.getOrDefault(oldValue, "") + ":" + oldValue);
											}
											if (newValue != null) {
												User newUser = getUser(client, newValue);
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
										} else if ("start_date".equals(name)) {
											String fieldName = importOption.getStartDateField();
											if (fieldName == null)
												fieldName = "Start date";
											addToFields(fieldName, oldValue, oldFields);
											addToFields(fieldName, newValue, newFields);
										} else if ("due_date".equals(name)) {
											String fieldName = importOption.getDueDateField();
											if (fieldName == null)
												fieldName = "Due date";
											addToFields(fieldName, oldValue, oldFields);
											addToFields(fieldName, newValue, newFields);
										} else if ("done_ratio".equals(name)) {
											String fieldName = importOption.getDoneRatioField();
											if (fieldName == null)
												fieldName = "Done Ratio";
											addToFields(fieldName, oldValue, oldFields);
											addToFields(fieldName, newValue, newFields);
										} else if ("estimated_hours".equals(name)) {
											String fieldName = importOption.getEstimatedHoursField();
											if (fieldName == null)
												fieldName = "Estimated Hours";
											addToFields(fieldName, oldValue, oldFields);
											addToFields(fieldName, newValue, newFields);
										} else {
											resultNotes.add(String.format(
												"Unknown history property name '%s' in Redmine issue <a href=\"%s\">#%d</a> (<a href=\"%s\">JSON</a>)",
												HtmlEscape.escapeHtml5(name), server.getApiEndpoint("/issues/" + oldNumber), oldNumber, apiEndpoint));
										}
									} else if ("relation".equals(property)) {
										String linkName;
										switch (detailNode.get("name").asText()) {
											case "relates":     linkName = "Related To"; break;
											case "duplicates":  linkName = "Duplicating"; break;
											case "duplicated":  linkName = "Duplicated By"; break;
											case "blocks":      linkName = "Blocking"; break;
											case "blocked":     linkName = "Blocked By"; break;
											case "precedes":    linkName = "Precedes"; break;
											case "follows":     linkName = "Follows"; break;
											case "copied_to":   linkName = "Copied To"; break;
											case "copied_from": linkName = "Copied From"; break;
											default:            linkName = "Unknown"; break;
										}
										data = new TempIssueLinkChangeData(linkName, oldValue, newValue);
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
									} else if ("attachment".equals(property) || "attachment_version".equals(property)) {
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

										lastUpdate.setDescription(issueChange.getData().getActivity());
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

									lastUpdate.setDescription(issueChange.getData().getActivity());
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

						issue.setLastActivity(lastUpdate);

						issues.add(issue);
						issuesMap.put(oldNumber, issue);
					}

					logger.log("Imported " + numOfImportedIssues.addAndGet(pageData.size()) + "/" + this.getTotal()  + " issues");
				}

			};


			String importIssueIDs = importOption.getImportIssueIDs();
			if (importIssueIDs != null) {
				int count = 0;
				StringBuilder ids = new StringBuilder();
				for (String id: importIssueIDs.split(",")) {
					id = id.trim();
					if (id.indexOf('-') > 0) {
						String[] split = id.split("-");
						if (split.length != 2)
							throw new ExplicitException("Invalid issue ID range '" + id + "'");

						try {
							int from = Integer.parseInt(split[0].trim());
							int to = Integer.parseInt(split[1].trim());
							for (int i = from; i <= to; i++) {
								if (ids.length() > 0)
									ids.append(',');
								ids.append(i);
								count++;
							}
						} catch (NumberFormatException ex) {
							throw new ExplicitException("Invalid issue ID range '" + id + "'");
						}
					} else {
						if (ids.length() > 0)
							ids.append(',');
						ids.append(id);
						count++;
					}
				}
				if (count > 300)
					throw new ExplicitException("Too many issue IDs (max 300).");

				importIssueIDs = ids.toString();
			}
			logger.log("Importing issues from project ID:" + redmineProjectId + "...");

			String apiEndpoint = server.getApiEndpoint("/issues.json?project_id=" + redmineProjectId + "&status_id=*&sort=id&assigned_to_id="
					+ (importIssueIDs != null ? "&issue_id=" + importIssueIDs : ""));
			rc.list(apiEndpoint, "issues", pageDataConsumer);

			// replace temporary link change data
			for (Issue issue : issues) {
				for (IssueChange change : issue.getChanges()) {
					if (change.getData() instanceof TempIssueLinkChangeData) {
						TempIssueLinkChangeData data = (TempIssueLinkChangeData) change.getData();
						String oldIssueSummary = null;
						if (data.oldValue != null) {
							Long oldNumber = new Long(data.oldValue);
							Issue oldIssue = issuesMap.get(oldNumber);
							oldIssueSummary = "#" + issueNumberMappings.getOrDefault(oldNumber, oldNumber)
								+ (oldIssue != null ? " - " + oldIssue.getTitle() : "");
						}
						String newIssueSummary = null;
						if (data.newValue != null) {
							Long newNumber = new Long(data.newValue);
							Issue newIssue = issuesMap.get(newNumber);
							newIssueSummary = "#" + issueNumberMappings.getOrDefault(newNumber, newNumber)
								+ (newIssue != null ? " - " + newIssue.getTitle() : "");
						}

						change.setData(new IssueTitleChangeData(oldIssueSummary, newIssueSummary));
					}
				}
			}

			// create OneDev links from Redmine relations
			List<LinkSpec> linkSpecs = new ArrayList<>();
			List<IssueLink> issueLinks = new ArrayList<>();
			Map<String, LinkSpec> relationTypeMapping = new HashMap<>();
			LinkSpecManager linkSpecManager = OneDev.getInstance(LinkSpecManager.class);
			for (JsonNode relationNode : redmineRelations.values()) {
				long issue_id = relationNode.get("issue_id").asLong();
				long issue_to_id = relationNode.get("issue_to_id").asLong();
				String relation_type = relationNode.get("relation_type").asText();

				Issue source = issuesMap.get(issue_to_id);
				Issue target = issuesMap.get(issue_id);

				if (source == null) {
					resultNotes.add(String.format(
							"Relation to unknown issue #%d in Redmine issue <a href=\"%s\">#%d</a>",
							issue_to_id, server.getApiEndpoint("/issues/" + issue_id), issue_id));
					continue;
				}
				if (target == null) {
					resultNotes.add(String.format(
							"Relation to unknown issue #%d in Redmine issue <a href=\"%s\">#%d</a>",
							issue_id, server.getApiEndpoint("/issues/" + issue_to_id), issue_to_id));
					continue;
				}

				// create OneDev link specs (if necessary)
				LinkSpec linkSpec = relationTypeMapping.computeIfAbsent(relation_type, k -> {
					LinkSpec spec;
					switch (relation_type) {
						default:
						case "relates":    spec = getOrCreateLinkSpec(linkSpecManager, "Related To", true, null, false, 10, linkSpecs); break;
						case "duplicates": spec = getOrCreateLinkSpec(linkSpecManager, "Duplicated By", true, "Duplicating", true, 11, linkSpecs); break;
						case "blocks":     spec = getOrCreateLinkSpec(linkSpecManager, "Blocked By", true, "Blocking", true, 12, linkSpecs); break;
						case "precedes":   spec = getOrCreateLinkSpec(linkSpecManager, "Follows", true, "Precedes", true, 13, linkSpecs); break;
						case "copied_to":  spec = getOrCreateLinkSpec(linkSpecManager, "Copied From", true, "Copied To", true, 14, linkSpecs); break;
					}
					return spec;
				});

				IssueLink link = new IssueLink();
				link.setSource(source);
				link.setTarget(target);
				link.setSpec(linkSpec);
				issueLinks.add(link);
			}

			// create OneDev links from Redmine subtasks
			if (!redmineParents.isEmpty()) {
				LinkSpec linkSpec = getOrCreateLinkSpec(linkSpecManager, "Child Issue", true, "Parent Issue", false, 15, linkSpecs);
				for (Entry<Long, Long> entry : redmineParents.entrySet()) {
					Long childNumber = entry.getKey();
					Long parentNumber = entry.getValue();

					Issue source = issuesMap.get(parentNumber);
					Issue target = issuesMap.get(childNumber);

					if (source == null) {
						resultNotes.add(String.format(
								"Unknown parent issue #%d in Redmine issue <a href=\"%s\">#%d</a>",
								parentNumber, server.getApiEndpoint("/issues/" + childNumber), childNumber));
						continue;
					}
					if (target == null) {
						resultNotes.add(String.format(
								"Unknown child issue #%d in Redmine issue <a href=\"%s\">#%d</a>",
								childNumber, server.getApiEndpoint("/issues/" + parentNumber), parentNumber));
						continue;
					}

					IssueLink link = new IssueLink();
					link.setSource(source);
					link.setTarget(target);
					link.setSpec(linkSpec);
					issueLinks.add(link);
				}
			}

			if (!dryRun) {
				ReferenceMigrator migrator = new ReferenceMigrator(Issue.class, issueNumberMappings);
				Dao dao = OneDev.getInstance(Dao.class);
				for (Issue issue: issues) {
					if (issue.getDescription() != null)
						issue.setDescription(migrator.migratePrefixed(issue.getDescription(), "#"));

					dao.persist(issue);
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
					for (IssueWatch watch: issue.getWatches())
						dao.persist(watch);
				}

				for (LinkSpec linkSpec: linkSpecs)
					linkSpecManager.create(linkSpec);

				for (IssueLink issueLink: issueLinks)
					dao.persist(issueLink);
			}

			ImportResult result = new ImportResult();
			result.nonExistentLogins.addAll(nonExistentLogins);
			result.nonExistentMilestones.addAll(nonExistentMilestones);
			result.unmappedIssueTypes.addAll(unmappedIssueTypes);
			result.unmappedIssuePriorities.addAll(unmappedIssuePriorities);
			result.unmappedIssueFields.addAll(unmappedIssueFields);
			result.tooLargeAttachments.addAll(tooLargeAttachments);
			result.notes.addAll(resultNotes);
			result.usersCreated.addAll(this.usersCreated);

			return result;
		}
		catch (Exception e) {
			e.printStackTrace();
			logger.error(e.getMessage(), e);
			throw e;
		}
		finally {
			client.close();
		}
	}

	private static void addToFields(String fieldName, String value, Map<String, Input> fields) {
		if (value != null && !value.isEmpty())
			fields.put(fieldName, new Input(fieldName, InputSpec.ENUMERATION, Collections.singletonList(value)));
	}

	private static LinkSpec getOrCreateLinkSpec(LinkSpecManager linkSpecManager, String name, boolean multiple,
			String oppositeName, boolean oppositeMultiple, int order, List<LinkSpec> linkSpecs) {

		LinkSpec spec = linkSpecManager.find(name);
		if (spec != null)
			return spec;

		spec = new LinkSpec();
		spec.setName(name);
		spec.setMultiple(multiple);
		if (oppositeName != null) {
			spec.setOpposite(new LinkSpecOpposite());
			spec.getOpposite().setName(oppositeName);
			spec.getOpposite().setMultiple(oppositeMultiple);
		}
		spec.setOrder(order);

		linkSpecs.add(spec);
		return spec;
	}

	void importVersions() {
		boolean addWikiToMilestoneDescription = this.importOption.isAddWikiToMilestoneDescription();

		Client client = server.newClient();

		try {
			RedmineClient rc = new RedmineClient(client, this.logger);

			List<Milestone> milestones = new ArrayList<>();
			logger.log("Importing versions from project ID:" + redmineProjectId + "...");
			String apiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/versions.json");
			for (JsonNode versionNode: rc.list(apiEndpoint, "versions")) {
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

				if (addWikiToMilestoneDescription) {
					String wikiPageId = versionNode.get("name").asText().replace(' ', '_').replace(".", "");
					apiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/wiki/" + wikiPageId + ".json");
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
				}

				milestones.add(milestone);
				oneDevProject.getMilestones().add(milestone);

				if (!dryRun)
					OneDev.getInstance(MilestoneManager.class).createOrUpdate(milestone);
			}
		} finally {
			client.close();
		}
	}

	private void importIssueCategories() {

		Client client = server.newClient();
		RedmineClient rc = new RedmineClient(client, this.logger);

		try {
			String categoryIssueField = importOption.getCategoryIssueField();

			GlobalIssueSetting issueSetting = getIssueSetting();
			for (FieldSpec field : issueSetting.getFieldSpecs()) {
				if (field.getName().equals(categoryIssueField)) {
					logger.log("Issue Category '" + categoryIssueField + "' already exists");
					return;
				}
			}

			List<Choice> choices = new ArrayList<>();
			logger.log("Importing issue categories from project ID:" + redmineProjectId + "...");
			String apiEndpoint = server.getApiEndpoint("/projects/" + redmineProjectId + "/issue_categories.json");
			for (JsonNode categoryNode: rc.list(apiEndpoint, "issue_categories")) {
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

	private static class TempIssueLinkChangeData extends IssueTitleChangeData {

		private static final long serialVersionUID = 1L;

		final String linkName;
		final String oldValue;
		final String newValue;

		public TempIssueLinkChangeData(String linkName, String oldValue, String newValue) {
			super(oldValue, newValue);
			this.linkName = linkName;
			this.oldValue = oldValue;
			this.newValue = newValue;
		}
	}
}
