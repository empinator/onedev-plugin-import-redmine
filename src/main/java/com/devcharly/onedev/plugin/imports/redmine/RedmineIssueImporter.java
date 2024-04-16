package com.devcharly.onedev.plugin.imports.redmine;

import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.NAME;
import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.buildImportOption;
import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.importIssues;
import static com.devcharly.onedev.plugin.imports.redmine.ImportUtils.importVersions;

import java.io.Serializable;
import java.util.*;

import com.google.common.collect.Lists;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.OneDev;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.imports.IssueImporter;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.persistence.TransactionManager;
import io.onedev.server.web.component.taskbutton.TaskResult;
import io.onedev.server.web.util.ImportStep;
import io.onedev.server.web.util.WicketUtils;

public class RedmineIssueImporter implements IssueImporter {

	private static final long serialVersionUID = 1L;

	@Override
	public String getName() {
		return NAME;
	}

	private final ImportStep<ImportServer> serverStep = new ImportStep<ImportServer>() {

		private static final long serialVersionUID = 1L;

		@Override
		public String getTitle() {
			return "Server Setup";
		}

		@Override
		protected ImportServer newSetting() {
			ImportServer where = new ImportServer();
			WicketUtils.getPage().setMetaData(ImportServer.META_DATA_KEY, where);
			return where;
		}

	};

	private final ImportStep<IssueImportSource> sourceStep = new ImportStep<IssueImportSource>() {

		private static final long serialVersionUID = 1L;

		@Override
		public String getTitle() {
			return "Source Step";
		}

		@Override
		protected IssueImportSource newSetting() {
            return new IssueImportSource();
		}
	};

	private final ImportStep<IssueImportOption> optionStep = new ImportStep<IssueImportOption>() {

		private static final long serialVersionUID = 1L;

		@Override
		public String getTitle() {
			return "Option Step";
		}

		@Override
		protected IssueImportOption newSetting() {
			ImportServer where = serverStep.getSetting();
			IssueImportSource what = sourceStep.getSetting();

//			ArrayList<String> redmineProjects = Lists.newArrayList(what.getProject());
			return buildImportOption(where);
		}

	};

	@Override
	public List<ImportStep<? extends Serializable>> getSteps() {
		return Lists.newArrayList(serverStep, sourceStep, optionStep);
	}

	@Override
	public TaskResult doImport(Long projectId, boolean dryRun, TaskLogger logger) {
		return OneDev.getInstance(TransactionManager.class).call(() -> {

			Project project = OneDev.getInstance(ProjectManager.class).load(projectId);
			ImportServer where = serverStep.getSetting();
			IssueImportSource what = sourceStep.getSetting();
			IssueImportOption how = optionStep.getSetting();

			if (how.isImportVersions()) {
				boolean addWikiToMilestoneDescription = how.isAddWikiToMilestoneDescription();
				importVersions(where, what.getProject(), project, dryRun, logger, addWikiToMilestoneDescription);
				if (!how.isImportIssues()) {
					return new TaskResult(true, new TaskResult.HtmlMessgae("Versions imported successfully"));
				}
			}

			Map<String, Optional<User>> users = new HashMap<>();
			ImportResult importResult = importIssues(where, what.getProject(), project, how, users, dryRun, logger);

			return new TaskResult(true, new TaskResult.HtmlMessgae(importResult.toHtml("Issues imported successfully")));

		});

	}

}
