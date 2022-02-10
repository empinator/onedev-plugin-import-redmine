package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.model.support.issue.field.spec.FieldSpec;
import io.onedev.server.web.editable.annotation.ChoiceProvider;
import io.onedev.server.web.editable.annotation.Editable;

@Editable
public class IssueFieldMapping implements Serializable {

	private static final long serialVersionUID = 1L;

	private String redmineIssueField;

	private String oneDevIssueField;

	@Editable(order=100, name="Redmine Issue Field")
	@NotEmpty
	public String getRedmineIssueField() {
		return redmineIssueField;
	}

	public void setRedmineIssueField(String redmineIssueField) {
		this.redmineIssueField = redmineIssueField;
	}

	@Editable(order=200, name="OneDev Issue Field")
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
		for (FieldSpec field: issueSetting.getFieldSpecs())
			choices.add(field.getName());
		return choices;
	}

}
