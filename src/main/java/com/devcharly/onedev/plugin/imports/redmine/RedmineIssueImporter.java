package com.devcharly.onedev.plugin.imports.redmine;

import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.NAME;
import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.buildImportOption;
import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.importIssues;
import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.importVersions;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.imports.IssueImporter;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.web.util.WicketUtils;

public class RedmineIssueImporter extends IssueImporter<ImportServer, IssueImportSource, IssueImportOption> {

	private static final long serialVersionUID = 1L;

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String doImport(ImportServer where, IssueImportSource what, IssueImportOption how, Project project,
			boolean dryRun, TaskLogger logger) {
		if (how.isImportVersions()) {
			importVersions(where, what.getProject(), project, dryRun, logger);
			if (!how.isImportIssues())
				return new ImportResult().toHtml("Versions imported successfully");
		}

		logger.log("Importing issues from project " + what.getProject() + "...");
		Map<String, Optional<User>> users = new HashMap<>();
		return importIssues(where, what.getProject(), project, false, how, users, dryRun, logger)
				.toHtml("Issues imported successfully");
	}

	@Override
	public IssueImportSource getWhat(ImportServer where, TaskLogger logger) {
		WicketUtils.getPage().setMetaData(ImportServer.META_DATA_KEY, where);
		return new IssueImportSource();
	}

	@Override
	public IssueImportOption getHow(ImportServer where, IssueImportSource what, TaskLogger logger) {
		return buildImportOption(where, Lists.newArrayList(what.getProject()), logger);
	}

}