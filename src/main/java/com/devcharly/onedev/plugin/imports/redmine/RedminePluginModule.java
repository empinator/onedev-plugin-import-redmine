package com.devcharly.onedev.plugin.imports.redmine;

import java.io.Serializable;
import java.util.Collection;

import com.google.common.collect.Lists;

import io.onedev.commons.loader.AbstractPluginModule;
import io.onedev.server.imports.IssueImporter;
import io.onedev.server.imports.IssueImporterContribution;

/**
 * NOTE: Do not forget to rename moduleClass property defined in the pom if you've renamed this class.
 *
 */
public class RedminePluginModule extends AbstractPluginModule {

	@Override
	protected void configure() {
		super.configure();

		contribute(IssueImporterContribution.class, new IssueImporterContribution() {

			@Override
			public Collection<IssueImporter> getImporters() {
				return Lists.newArrayList(new RedmineIssueImporter());
			}

			@Override
			public int getOrder() {
				return 370;
			}

		});
	}

}
