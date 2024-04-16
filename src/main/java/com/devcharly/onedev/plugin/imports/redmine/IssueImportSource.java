package com.devcharly.onedev.plugin.imports.redmine;

import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.list;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.ws.rs.client.Client;

import io.onedev.server.annotation.ChoiceProvider;
import io.onedev.server.annotation.Editable;
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

	private String project;

	@Editable(order=100, name="Redmine Project", description="Choose Redmine project to import issues from")
	@ChoiceProvider("getProjectChoices")
	@NotEmpty
	public String getProject() {
		return project;
	}

	public void setProject(String project) {
		this.project = project;
	}

	@SuppressWarnings("unused")
	private static List<String> getProjectChoices() {
		List<String> choices = new ArrayList<>();

		ImportServer server = WicketUtils.getPage().getMetaData(ImportServer.META_DATA_KEY);

		Client client = server.newClient();
		try {
			String apiEndpoint = server.getApiEndpoint("/projects.json");
			TaskLogger logger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					IssueImportSource.logger.info(message);
				}

			};
			for (JsonNode projectNode: list(client, apiEndpoint, "projects", logger)) {
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
