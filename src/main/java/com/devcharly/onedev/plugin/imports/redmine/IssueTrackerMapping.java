package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.inputspec.InputSpec;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;
import io.onedev.server.web.editable.annotation.ChoiceProvider;
import io.onedev.server.web.editable.annotation.Editable;

@Editable
public class IssueTrackerMapping implements Serializable {

	private static final long serialVersionUID = 1L;

	private String redmineIssueTracker;

	private String oneDevIssueField;

	@Editable(order=100, name="Redmine Issue Tracker")
	@NotEmpty
	public String getRedmineIssueTracker() {
		return redmineIssueTracker;
	}

	public void setRedmineIssueTracker(String redmineIssueTracker) {
		this.redmineIssueTracker = redmineIssueTracker;
	}

	@Editable(order=200, name="OneDev Issue Field", description="Specify a custom field of Enum type")
	@ChoiceProvider("getOneDevIssueFieldChoices")
	@NotEmpty
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

}
