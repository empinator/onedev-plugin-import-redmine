package com.devcharly.onedev.plugin.imports.redmine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.unbescape.html.HtmlEscape;

public class ImportResult {

	private static final int MAX_DISPLAY_ENTRIES = 100;

	Set<String> nonExistentLogins = new HashSet<>();

	Set<String> nonExistentMilestones = new HashSet<>();

	boolean issuesImported = false;

	private String getEntryFeedback(String entryDescription, Collection<String> entries) {
		if (entries.size() > MAX_DISPLAY_ENTRIES) {
			List<String> entriesToDisplay = new ArrayList<>(entries).subList(0, MAX_DISPLAY_ENTRIES);
			return "<li> " + entryDescription + ": " + HtmlEscape.escapeHtml5(entriesToDisplay.toString()) + " and more";
		} else {
			return "<li> " + entryDescription + ": " + HtmlEscape.escapeHtml5(entries.toString());
		}
	}

	public String toHtml(String leadingText) {
		StringBuilder feedback = new StringBuilder(leadingText);

		boolean hasNotice = false;

		if (!nonExistentMilestones.isEmpty()
				|| !nonExistentLogins.isEmpty() || issuesImported) {
			hasNotice = true;
		}

		if (hasNotice)
			feedback.append("<br><br><b>NOTE:</b><ul>");

		if (!nonExistentMilestones.isEmpty())
			feedback.append(getEntryFeedback("Non existent milestones", nonExistentMilestones));
		if (!nonExistentLogins.isEmpty()) {
			feedback.append(getEntryFeedback("Redmine logins without public email or public email can not be mapped to OneDev account",
					nonExistentLogins));
		}

		if (hasNotice)
			feedback.append("</ul>");

		return feedback.toString();

	}
}
