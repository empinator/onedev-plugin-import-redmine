package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.inputspec.InputSpec;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;
import io.onedev.server.web.editable.annotation.ChoiceProvider;
import io.onedev.server.web.editable.annotation.Editable;

@Editable
public class IssueImportOption implements Serializable {

	private static final long serialVersionUID = 1L;

	private boolean importIssues = true;

	private boolean importVersions;

	private String importIssueIDs;

	private String assigneesIssueField = "Assignees";
	private String categoryIssueField = "Category";

	private List<IssueStatusMapping> issueStatusMappings = new ArrayList<>();
	private List<IssueTrackerMapping> issueTrackerMappings = new ArrayList<>();
	private List<IssuePriorityMapping> issuePriorityMappings = new ArrayList<>();
	private List<IssueFieldMapping> issueFieldMappings = new ArrayList<>();

	@Editable(order=100, name="Import Issues")
	@Nullable
	public boolean isImportIssues() {
		return importIssues;
	}

	public void setImportIssues(boolean importIssues) {
		this.importIssues = importIssues;
	}

	@Editable(order=200, name="Import Versions")
	@Nullable
	public boolean isImportVersions() {
		return importVersions;
	}

	public void setImportVersions(boolean importVersions) {
		this.importVersions = importVersions;
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
			+ "<b>NOTE: </b> The custom field is created if it does not exist")
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

	@Editable(order=380, description="Specify how to map Redmine custom issue fields to OneDev. Unmapped fields will "
			+ "be reflected in issue description.<br>"
			+ "<b>Note: </b> You may customize OneDev issue fields in case there is no appropriate option here")
	public List<IssueFieldMapping> getIssueFieldMappings() {
		return issueFieldMappings;
	}

	public void setIssueFieldMappings(List<IssueFieldMapping> issueFieldMappings) {
		this.issueFieldMappings = issueFieldMappings;
	}

}
