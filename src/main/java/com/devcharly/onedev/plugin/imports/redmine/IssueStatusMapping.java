package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.validator.constraints.NotEmpty;

import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.support.administration.GlobalIssueSetting;
import io.onedev.server.web.editable.annotation.ChoiceProvider;
import io.onedev.server.web.editable.annotation.Editable;

@Editable
public class IssueStatusMapping implements Serializable {

	private static final long serialVersionUID = 1L;

	private String redmineIssueStatus;

	private String oneDevIssueState;

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

	static List<String> getOneDevIssueStateChoices() {
		GlobalIssueSetting issueSetting = OneDev.getInstance(SettingManager.class).getIssueSetting();
		return issueSetting.getStateSpecs().stream().map(it->it.getName()).collect(Collectors.toList());
	}

}
