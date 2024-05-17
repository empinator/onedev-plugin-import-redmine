package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import io.onedev.server.annotation.ChoiceProvider;
import io.onedev.server.annotation.Editable;
import io.onedev.server.buildspecmodel.inputspec.InputSpec;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;
import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.GlobalIssueSetting;

@Editable
public class IssueStatusMapping implements Serializable {

	private static final long serialVersionUID = 1L;

	private String redmineIssueStatus;

	private String oneDevIssueState;

	private String oneDevIssueField;


	// map status required !
	// optional: add label and/or set custom field

	private boolean addLabel;

	@Editable(order=100, name="Redmine Issue Status")
	@NotEmpty
	public String getRedmineIssueStatus() {
		return redmineIssueStatus;
	}

	public void setRedmineIssueStatus(String redmineIssueStatus) {
		this.redmineIssueStatus = redmineIssueStatus;
	}

	@Editable(order=200, name="OneDev Issue State", description="OneDev Issue State")
	@ChoiceProvider("getOneDevIssueStateChoices")
	@NotEmpty
	public String getOneDevIssueState() {
		return oneDevIssueState;
	}

	public void setOneDevIssueState(String oneDevIssueState) {
		this.oneDevIssueState = oneDevIssueState;
	}

	@Editable(order=200, name="Set Issue Field", description="Optionally, specify a custom field of Enum type to set")
	@ChoiceProvider("getOneDevIssueFieldChoices")
	public String getOneDevIssueField() {
		return oneDevIssueField;
	}

	public void setOneDevIssueField(String oneDevIssueField) {
		this.oneDevIssueField = oneDevIssueField;
	}

	static List<String> getOneDevIssueFieldChoices() {
		List<String> choices = new ArrayList<>();
		GlobalIssueSetting issueSetting = OneDev.getInstance(SettingManager.class).getIssueSetting();
		for (FieldSpec field: issueSetting.getFieldSpecs()) {
			if (field.getType().equals(InputSpec.ENUMERATION)) {
				for (String value: field.getPossibleValues())
					choices.add(field.getName() + "::" + value);
			}
		}
		return choices;
	}

	static List<String> getOneDevIssueStateChoices() {
		GlobalIssueSetting issueSetting = OneDev.getInstance(SettingManager.class).getIssueSetting();
		return issueSetting.getStateSpecs().stream().map(it->it.getName()).collect(Collectors.toList());
	}

}
