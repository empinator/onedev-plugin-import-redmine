package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import io.onedev.server.annotation.ChoiceProvider;
import io.onedev.server.annotation.Editable;
import io.onedev.server.annotation.GroupChoice;
import io.onedev.server.annotation.ShowCondition;
import io.onedev.server.buildspecmodel.inputspec.InputSpec;
import io.onedev.server.model.support.administration.sso.SsoConnector;
import io.onedev.server.util.EditContext;
import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;

@Editable
public class IssueImportOption implements Serializable {

	private static final long serialVersionUID = 1L;

	private boolean createUser;
	private boolean createAsGuestUser;
	private boolean createAsExternal;
	private String ssoConnector;
	private String assignUsersToGroup;

	private boolean importIssues = true;
	private boolean importVersions;
	private boolean addWikiToMilestoneDescription = false;
	private boolean convertTextileToMarkdown;
	private boolean useExistingIssueIDs = true;

	private String importIssueIDs;

	private String assigneesIssueField = "Assignees";
	private String categoryIssueField = "Category";
	private String startDateField;
	private String dueDateField;
	private String doneRatioField;
	private String estimatedHoursField;

	private List<IssueStatusMapping> issueStatusMappings = new ArrayList<>();
	private List<IssueTrackerMapping> issueTrackerMappings = new ArrayList<>();
	private List<IssuePriorityMapping> issuePriorityMappings = new ArrayList<>();
	private List<IssueFieldMapping> issueFieldMappings = new ArrayList<>();

	@Editable(order=10, name="Create Missing Users",
			description = "If enabled, users existing Redmine will be created in OneDev"
			+ " If disabled, content will be linked to 'unknown'")
	public boolean isCreateUser() {
		return createUser;
	}

	public void setCreateUser(boolean createUser) {
		this.createUser = createUser;
	}

	private static boolean isCreateUserEnabled() {
		return (Boolean) EditContext.get().getInputValue("createUser");
	}

	@Editable(order=11, name="Create Users as Guests")
	@ShowCondition("isCreateUserEnabled")
	public boolean isCreateAsGuestUser() {
		return createAsGuestUser;
	}

	public void setCreateAsGuestUser(boolean createAsGuestUser) {
		this.createAsGuestUser = createAsGuestUser;
	}

	@Editable(order=12, name="Create Users as managed externally, e.g. if you have an Active Directory configured")
	@ShowCondition("isCreateUserEnabled")
	public boolean isCreateAsExternal() {
		return createAsExternal;
	}

	public void setCreateAsExternal(boolean createAsExternal) {
		this.createAsExternal = createAsExternal;
	}

	@Editable(order=12, name="Select external SSO Provider to set for user. Leave Blank for 'External Authentication'")
	@ShowCondition("isCreateUserEnabled")
	@ChoiceProvider("getAvailableSsoConnectors")
	public String getSsoConnector() {
		return ssoConnector;
	}

	public void setSsoConnector(String ssoConnector) {
		this.ssoConnector = ssoConnector;
	}

	private static List<String> getAvailableSsoConnectors() {
		List<String> choices = new ArrayList<>();
		List<SsoConnector> ssoConnectors = OneDev.getInstance(SettingManager.class).getSsoConnectors();
		for (SsoConnector field: ssoConnectors) {
			choices.add(field.getName());
		}
		return choices;
	}

	@Editable(order=13, name="Automatically assign existing and newly created users to the selected group")
	@GroupChoice
	public String getAssignUsersToGroup() {
		return assignUsersToGroup;
	}

	public void setAssignUsersToGroup(String assignUsersToGroup) {
		this.assignUsersToGroup = assignUsersToGroup;
	}

	@Editable(order=100,  name="Import Issues")
	public boolean isImportIssues() {
		return importIssues;
	}

	public void setImportIssues(boolean importIssues) {
		this.importIssues = importIssues;
	}

	@Editable(order=200, name="Import Versions")
	public boolean isImportVersions() {
		return importVersions;
	}

	public void setImportVersions(boolean importVersions) {
		this.importVersions = importVersions;
	}

	@Editable(order=205, name="Add Redmine Wiki to all Milestone Descriptions")
	public boolean isAddWikiToMilestoneDescription() {
		return addWikiToMilestoneDescription;
	}

	public void setAddWikiToMilestoneDescription(boolean addWikiToMilestoneDescription) {
		this.addWikiToMilestoneDescription = addWikiToMilestoneDescription;
	}

	@Editable(order=210)
	public boolean isConvertTextileToMarkdown() {
		return convertTextileToMarkdown;
	}

	public void setConvertTextileToMarkdown(boolean convertTextileToMarkdown) {
		this.convertTextileToMarkdown = convertTextileToMarkdown;
	}

	@Editable(order=220, name="Use existing issue IDs",
			description = "If enabled, use existing Redmine issue IDs for OneDev issues."
					+ " If disabled, imported issues (may) get different IDs.")
	public boolean isUseExistingIssueIDs() {
		return useExistingIssueIDs;
	}

	public void setUseExistingIssueIDs(boolean useExistingIssueIDs) {
		this.useExistingIssueIDs = useExistingIssueIDs;
	}

	@Editable(order=250, name="Import Issue IDs", description="Redmine issue IDs to import (all if empty; multiple IDs separated by <code>,</code>; ID range separated by <code>-</code>; maximum 300 issues).")
	public String getImportIssueIDs() {
		return importIssueIDs;
	}

	public void setImportIssueIDs(String importIssueIDs) {
		this.importIssueIDs = importIssueIDs;
	}

	private static GlobalIssueSetting getIssueSetting() {
		return OneDev.getInstance(SettingManager.class).getIssueSetting();
	}

	@Editable(order=350, description="Specify a multi-value user field to hold assignees information.<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	@ChoiceProvider("getAssigneesIssueFieldChoices")
	@NotEmpty
	public String getAssigneesIssueField() {
		return assigneesIssueField;
	}

	public void setAssigneesIssueField(String assigneesIssueField) {
		this.assigneesIssueField = assigneesIssueField;
	}

	@SuppressWarnings("unused")
	private static List<String> getAssigneesIssueFieldChoices() {
		List<String> choices = new ArrayList<>();
		for (FieldSpec field: getIssueSetting().getFieldSpecs()) {
			if (field.getType().equals(InputSpec.USER) && field.isAllowMultiple())
				choices.add(field.getName());
		}
		return choices;
	}

	@Editable(order=360, description="Specify a custom field to hold category information.<br>"
			+ "<b>NOTE: </b> If the custom field does <b>not exist</b>, it is <b>created</b> with possible values imported from Redmine")
	@NotEmpty
	public String getCategoryIssueField() {
		return categoryIssueField;
	}

	public void setCategoryIssueField(String categoryIssueField) {
		this.categoryIssueField = categoryIssueField;
	}

	@Editable(order=600, description="Specify how to map Redmine issue statuses to OneDev custom fields.<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue states in case there is no appropriate option here")
	public List<IssueStatusMapping> getIssueStatusMappings() {
		return issueStatusMappings;
	}

	public void setIssueStatusMappings(List<IssueStatusMapping> issueStatusMappings) {
		this.issueStatusMappings = issueStatusMappings;
	}

	@Editable(order=370, description="Optionally specify a date field to hold start date information."
			+ " If not specified, the field will be reflected in issue description<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	@ChoiceProvider("getDateIssueFieldChoices")
	public String getStartDateField() {
		return startDateField;
	}

	public void setStartDateField(String startDateField) {
		this.startDateField = startDateField;
	}

	@Editable(order=371, description="Optionally specify a date field to hold due date information."
			+ " If not specified, the field will be reflected in issue description<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	@ChoiceProvider("getDateIssueFieldChoices")
	public String getDueDateField() {
		return dueDateField;
	}

	public void setDueDateField(String dueDateField) {
		this.dueDateField = dueDateField;
	}

	@SuppressWarnings("unused")
	private static List<String> getDateIssueFieldChoices() {
		List<String> choices = new ArrayList<>();
		for (FieldSpec field: getIssueSetting().getFieldSpecs()) {
			if (field.getType().equals(InputSpec.DATE))
				choices.add(field.getName());
		}
		return choices;
	}

	@Editable(order=372, description="Optionally specify a integer field to hold done ratio (percentage) information."
			+ " If not specified, the field will be reflected in issue description<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	@ChoiceProvider("getIntegerIssueFieldChoices")
	public String getDoneRatioField() {
		return doneRatioField;
	}

	public void setDoneRatioField(String doneRatioField) {
		this.doneRatioField = doneRatioField;
	}

	@Editable(order=373, description="Optionally specify a integer field to hold estimated hours information."
			+ " If not specified, the field will be reflected in issue description<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	@ChoiceProvider("getIntegerIssueFieldChoices")
	public String getEstimatedHoursField() {
		return estimatedHoursField;
	}

	public void setEstimatedHoursField(String estimatedHoursField) {
		this.estimatedHoursField = estimatedHoursField;
	}

	@SuppressWarnings("unused")
	private static List<String> getIntegerIssueFieldChoices() {
		List<String> choices = new ArrayList<>();
		for (FieldSpec field: getIssueSetting().getFieldSpecs()) {
			if (field.getType().equals(InputSpec.INTEGER))
				choices.add(field.getName());
		}
		return choices;
	}

	@Editable(order=700, description="Specify how to map Redmine issue trackers to OneDev custom fields.<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	public List<IssueTrackerMapping> getIssueTrackerMappings() {
		return issueTrackerMappings;
	}

	public void setIssueTrackerMappings(List<IssueTrackerMapping> issueTrackerMappings) {
		this.issueTrackerMappings = issueTrackerMappings;
	}

	@Editable(order=800, description="Specify how to map Redmine issue priorities to OneDev custom fields.<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	public List<IssuePriorityMapping> getIssuePriorityMappings() {
		return issuePriorityMappings;
	}

	public void setIssuePriorityMappings(List<IssuePriorityMapping> issuePriorityMappings) {
		this.issuePriorityMappings = issuePriorityMappings;
	}

	@Editable(order=380, name="Custom Issue Field Mappings",
			description="Specify how to map Redmine custom issue fields to OneDev. Unmapped fields will "
			+ "be reflected in issue description.<br>"
			+ "<b>Note: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	public List<IssueFieldMapping> getIssueFieldMappings() {
		return issueFieldMappings;
	}

	public void setIssueFieldMappings(List<IssueFieldMapping> issueFieldMappings) {
		this.issueFieldMappings = issueFieldMappings;
	}

}
