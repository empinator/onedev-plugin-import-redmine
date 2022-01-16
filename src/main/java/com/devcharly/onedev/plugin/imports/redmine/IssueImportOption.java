package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

	private String closedIssueState = "Released";
	private String rejectedIssueState = "Closed";

	private String assigneesIssueField;

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

	@Editable(order=300, description="Specify which issue state to use for closed Redmine issues.<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue states in case there is no appropriate option here")
	@ChoiceProvider("getCloseStateChoices")
	@NotEmpty
	public String getClosedIssueState() {
		return closedIssueState;
	}

	public void setClosedIssueState(String closedIssueState) {
		this.closedIssueState = closedIssueState;
	}

	@Editable(order=301, description="Specify which issue state to use for rejected Redmine issues.<br>"
			+ "<b>NOTE: </b> You may customize OneDev issue states in case there is no appropriate option here")
	@ChoiceProvider("getCloseStateChoices")
	@NotEmpty
	public String getRejectedIssueState() {
		return rejectedIssueState;
	}

	public void setRejectedIssueState(String rejectedIssueState) {
		this.rejectedIssueState = rejectedIssueState;
	}

	private static GlobalIssueSetting getIssueSetting() {
		return OneDev.getInstance(SettingManager.class).getIssueSetting();
	}

	@SuppressWarnings("unused")
	private static List<String> getCloseStateChoices() {
		List<String> choices = getIssueSetting().getStateSpecs().stream()
				.map(it->it.getName()).collect(Collectors.toList());
		choices.remove(0);
		return choices;
	}

	@Editable(order=350, description="Specify a multi-value user field to hold assignees information."
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

}
