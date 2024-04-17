package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.client.Client;

import io.onedev.server.annotation.ChoiceProvider;
import io.onedev.server.annotation.Editable;
import io.onedev.server.annotation.ShowCondition;
import io.onedev.server.util.EditContext;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.web.util.WicketUtils;

@Editable
public class IssueImportSource implements Serializable {

	private static final long serialVersionUID = 1L;

	private static final Logger logger = LoggerFactory.getLogger(IssueImportSource.class);

	private boolean dontSearch = false;

	private String projectSelection;
	private String projectId;


	@Editable(order=50, name="Don't Search for Projects, use provided Project ID",
			description = "If enabled, the next field won't be prefilled with available projects but use the provided ID instead")
	public boolean isDontSearch() {
		return dontSearch;
	}

	public void setDontSearch(boolean dontSearch) {
		this.dontSearch = dontSearch;
	}

	@Editable(order=100, name="Redmine Project", description="Choose Redmine project to import issues from")
	@ChoiceProvider("getProjectChoices")
	@NotEmpty
	@ShowCondition("isDontSearchDisabled")
	public String getProjectSelection() {
		return projectSelection;
	}

	public void setProjectSelection(String projectSelection) {
		this.projectSelection = projectSelection;
	}

	@Editable(order=100, name="Redmine Project ID", description="Provide Redmine Project ID (not the name/identifier) to be used in API calls")
	@NotEmpty
	@ShowCondition("isDontSearchEnabled")
	public String getProjectId() {
		return projectId;
	}

	public void setProjectId(String projectId) {
		this.projectId = projectId;
	}

	public static boolean isDontSearchEnabled() {
		return (Boolean) EditContext.get().getInputValue("dontSearch");
	}
	public static boolean isDontSearchDisabled() {
		return !isDontSearchEnabled();
	}

	String getRedmineProjectId() {
		if(isDontSearch()) {
			return this.getProjectId();
		}
		int sep = this.projectSelection.lastIndexOf(':');
		return this.projectSelection.substring(sep + 1);
	}

	@SuppressWarnings("unused")
	private static List<String> getProjectChoices() {
		List<String> choices = new ArrayList<>();

		if( isDontSearchEnabled() ) {
			return choices;
		}

		ImportServer server = WicketUtils.getPage().getMetaData(ImportServer.META_DATA_KEY);

		Client client = server.newClient();
		TaskLogger logger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				IssueImportSource.logger.info(message);
			}

		};
		RedmineClient rm = new RedmineClient(client, logger);

		try {
			String apiEndpoint = server.getApiEndpoint("/projects.json");
			for (JsonNode projectNode: rm.list(apiEndpoint, "projects")) {
				String projectName = projectNode.get("name").asText();
				String projectId = projectNode.get("id").asText();
				choices.add(projectName + ":" + projectId);
			}
		} finally {
			client.close();
		}

		Collections.sort(choices);
		return choices;
	}

}
