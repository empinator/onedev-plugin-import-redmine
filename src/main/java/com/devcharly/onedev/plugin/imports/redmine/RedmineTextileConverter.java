package com.devcharly.onedev.plugin.imports.redmine;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

/*

https://www.redmine.org/projects/redmine/wiki/RedmineTextFormattingTextile

Textile                      Markdown
-------                      --------

h1. heading                  # heading
h2. heading                  ## heading
h3. heading                  ### heading
h4. heading                  #### heading

* unordered list             - unordered list
** unordered list              - unordered list

# ordered list               1. ordered list
## ordered list                 1. ordered list

*bold*                       **bold**
**bold**                     **bold**            (no change)
_italic_                     _italic_            (no change)
__italic__                   _italic_
+underline+                  ++underline++
-strike-through-             ~~strike-through~~
@inline code@                `inline code`

<pre>                        ~~~
pre-formatted                pre-formatted
</pre>                       ~~~

<pre><code class="java">     ~~~java
pre-formatted                pre-formatted
</code></pre>                ~~~

> <pre>                      > ~~~
quoted pre-formatted         > quoted pre-formatted
</pre>                       > ~~~

commit:123456789abcdef       123456789abcdef
[[wiki-link]]                wiki-link
!image_url!                  ![image_url](image_url)
!>image_url!                 ![image_url](image_url)
!image_url(Image title)!     ![Image title](image_url)

*/

class RedmineTextileConverter {

	private static final String
		H1_MATCH = "(?m)^\\h*h1\\.\\h+",
		H2_MATCH = "(?m)^\\h*h2\\.\\h+",
		H3_MATCH = "(?m)^\\h*h3\\.\\h+",
		H4_MATCH = "(?m)^\\h*h4\\.\\h+",

		H1_REPLACE = "# ",
		H2_REPLACE = "## ",
		H3_REPLACE = "### ",
		H4_REPLACE = "#### ",

		UNORDERED_LIST_MATCH   = "(?m)^(\\h*)(\\*{1,10})(?=\\h+[^\\h\\n])",
		ORDERED_LIST_MATCH     = "(?m)^(\\h*)(#{1,10})(?=\\h+[^\\h\\n])",

		ITALIC_MATCH           = "(?m)(?<=^|\\h|\\|)__(?!\\h|_)(.+?)(?<!\\h)__(?=$|\\W|\\|)",
		BOLD_MATCH             = "(?m)(?<=^|\\h|\\|)\\*(?!\\h|\\*)(.+?)(?<!\\h)\\*(?=$|\\W|\\|)",
		UNDERLINE_MATCH        = "(?m)(?<=^|\\h|\\|)\\+(?!\\h|\\+)(.+?)(?<!\\h)\\+(?=$|\\W|\\|)",
		STRIKE_THROUGH_MATCH   = "(?m)(?<=^|\\h|\\|)\\-(?!\\h|\\-)(.+?)(?<!\\h)\\-(?=$|\\W|\\|)",
		INLINE_CODE_MATCH      = "(?m)(?<=^|\\h|\\|)@(?!\\h|@)(.+?)(?<!\\h)@(?=$|\\W|\\|)",

		ITALIC_REPLACE         = "_$1_",
		BOLD_REPLACE           = "**$1**",
		UNDERLINE_REPLACE      = "++$1++",
		STRIKE_THROUGH_REPLACE = "~~$1~~",

		QUOTED_PRE_MATCH       = "(?ms)^(>\\h*<pre>)(.*?)(</pre>)",
		PRE_MATCH              = "(?s)(\\h*|>\\s*)<pre>\\h*\\n?(.*?)\\n?(\\h*|>\\s*)</pre>\\h*\\n?",
		PRE_CODE_MATCH         = "(?s)^\\s*<code\\s+class=\"(.*?)\">\\h*\\n?(.*?)\\n?\\h*<\\/code>\\s*$",
		PRE_CODE_REPLACE       = "$1\n$2",

		COMMIT_MATCH           = "(?m)(?<=^|\\h)commit:(\\\"?)([\\da-fA-F]{8,})\\1",
		COMMIT_REPLACE         = "$2 ",

		WIKI_LINK_MATCH        = "\\[\\[([^#\\|:]+?)\\]\\]",
		WIKI_LINK_REPLACE      = "$1",

		IMAGE_MATCH            = "(?mi)(?<=^|\\h)!>?(?!\\h)(.+?(?:\\.png|\\.gif|\\.jpg|\\.jpeg))!",
		IMAGE_TITLE_MATCH      = "(?mi)(?<=^|\\h)!>?(?!\\h)(.+?(?:\\.png|\\.gif|\\.jpg|\\.jpeg))\\((.+?)\\)!",
		IMAGE_REPLACE          = "![$1]($1)",
		IMAGE_TITLE_REPLACE    = "![$2]($1)";


	static String convertTextileToMarkdown(String str) {
		if (str == null || str.isEmpty())
			return str;

		// quoted pre-formatted text
		str = replace(str, QUOTED_PRE_MATCH, matcher -> {
			String content = matcher.group(2);
			if (!content.startsWith("\n"))
				content = '\n' + content;
			content = content.replaceAll("\\n(?!>)", "\n> ");
			if (!content.endsWith("\n> "))
				content += "\n> ";
			return matcher.group(1) + content + matcher.group(3);
		});

		// pre-formatted text
		// replace all text between <pre> and </pre> with temporary tags
		// to exclude it from further processing
		HashMap<String, String[]> tempPreTags = new HashMap<>();
		AtomicInteger preId = new AtomicInteger();
		str = replace(str, PRE_MATCH, matcher -> {
			String tempTag = "<temptag-pre-" + preId.incrementAndGet() + ">";
			tempPreTags.put(tempTag, new String[] {
					matcher.group(1),
					matcher.group(2),
					matcher.group(3),
			});
			return tempTag;
		});

		// inline code
		// replace all inline code (@code@) with temporary tags
		// to exclude it from further processing (e.g. for @*text*@)
		HashMap<String, String> tempTags = new HashMap<>();
		AtomicInteger tempId = new AtomicInteger();
		str = replace(str, INLINE_CODE_MATCH, matcher -> {
			String tempTag = "<temptag-code-" + tempId.incrementAndGet() + ">";
			tempTags.put(tempTag, "`" + matcher.group(1) + "`");
			return tempTag;
		});

		// unordered lists
		str = replace(str, UNORDERED_LIST_MATCH, matcher -> {
			return matcher.group(1) + StringUtils.repeat(" ", (matcher.group(2).length() - 1) * 2) + "-";
		});

		// ordered lists
		str = replace(str, ORDERED_LIST_MATCH, matcher -> {
			return matcher.group(1) + StringUtils.repeat(" ", (matcher.group(2).length() - 1) * 3) + "1.";
		});

		// headings
		str = str.replaceAll(H1_MATCH, H1_REPLACE);
		str = str.replaceAll(H2_MATCH, H2_REPLACE);
		str = str.replaceAll(H3_MATCH, H3_REPLACE);
		str = str.replaceAll(H4_MATCH, H4_REPLACE);

		// __italic__ -> _italic_
		str = str.replaceAll(ITALIC_MATCH, ITALIC_REPLACE);

		// *bold* -> **bold**
		str = str.replaceAll(BOLD_MATCH, BOLD_REPLACE);

		// +underline+ -> ++underline++
		str = str.replaceAll(UNDERLINE_MATCH, UNDERLINE_REPLACE);

		// -strike-through- -> ~~strike-through~~
		str = str.replaceAll(STRIKE_THROUGH_MATCH, STRIKE_THROUGH_REPLACE);

		// remove prefix "commit:" from commit links
		str = str.replaceAll(COMMIT_MATCH, COMMIT_REPLACE);

		// remove simple wiki links
		str = str.replaceAll(WIKI_LINK_MATCH, WIKI_LINK_REPLACE);

		// inline images
		str = str.replaceAll(IMAGE_TITLE_MATCH, IMAGE_TITLE_REPLACE);
		str = str.replaceAll(IMAGE_MATCH, IMAGE_REPLACE);

		// replace temporary tags
		for (Entry<String, String> e : tempTags.entrySet())
			str = str.replace(e.getKey(), e.getValue());

		// replace temporary tags with markdown code blocks
		for (Entry<String, String[]> e : tempPreTags.entrySet()) {
			String[] values = e.getValue();
			String value = values[1];
			String value2 = value.replaceAll(PRE_CODE_MATCH, PRE_CODE_REPLACE);

			String replacement = values[0].startsWith(">") ? "> ~~~" : "~~~";
			if (value2.equals(value))
				replacement += '\n';
			replacement += value2;
			replacement += values[2].startsWith(">") ? "\n> ~~~\n" : "\n~~~\n";

			str = str.replace(e.getKey(), replacement);
		}

		return str;
	}

	private static String replace( String str, String regex, Function<Matcher, String> callback ) {
		Matcher matcher = Pattern.compile( regex ).matcher( str );

		boolean result = matcher.find();
		if( !result )
			return str;

		StringBuffer sb = new StringBuffer();
		do {
			matcher.appendReplacement( sb, Matcher.quoteReplacement( callback.apply( matcher ) ) );
			result = matcher.find();
		} while( result );
		matcher.appendTail( sb );
		return sb.toString();
	}

/*
	public static void main(String[] args) {
		testBold( "*bold*", "<x>bold</x>" );
		testBold( " *bold*", " <x>bold</x>" );
		testBold( "*bold* ", "<x>bold</x> " );

		testBold( "text *bold* text", "text <x>bold</x> text" );
		testBold( "text *bold*", "text <x>bold</x>" );
		testBold( "*bold* text", "<x>bold</x> text" );

		testBold( "|*bold*|", "|<x>bold</x>|" );
		testBold( "|*bold*", "|<x>bold</x>" );
		testBold( "*bold*|", "<x>bold</x>|" );

		testBold( "*bold*.", "<x>bold</x>." );
		testBold( ".*notbold*", null );

		testBold( "text *bold*bold* text", "text <x>bold*bold</x> text" );

		testBold( "* notbold*", null );
		testBold( "*notbold *", null );
		testBold( "text*notbold*", null );
		testBold( "*notbold*text", null );

		testBold( "**", null );
		testBold( "***", null );
		testBold( "****", null );
	}

	private static void testBold(String input, String expeded) {
		if (expeded == null)
			expeded = input;
		assertEquals(expeded, input.replaceAll(BOLD_MATCH, "<x>$1</x>"));
	}

	private static void assertEquals(Object expected, Object actual) {
		if (!java.util.Objects.equals(expected, actual))
			throw new RuntimeException(expected + " - " + actual);
	}
*/
}
