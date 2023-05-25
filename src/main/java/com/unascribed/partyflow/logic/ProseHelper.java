/*
 * This file is part of Partyflow.
 *
 * Partyflow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Partyflow is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with Partyflow.
 *
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.unascribed.partyflow.logic;

import java.io.IOException;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import com.unascribed.partyflow.handler.util.MultipartData;

import com.google.common.base.Strings;

public class ProseHelper {

	public static String getSafeHtml(MultipartData data, String key, boolean allowImages) throws IOException {
		String md = data.getPartAsString(key+".md", 65536);
		String html;
		if (md != null) {
			Parser parser = Parser.builder().build();
			HtmlRenderer rend = HtmlRenderer.builder().build();
			html = sanitizeHtml(rend.render(parser.parse(md)), allowImages);
		} else {
			html = sanitizeHtml(Strings.nullToEmpty(data.getPartAsString(key+".html", 65536)), allowImages);
		}
		return html;
	}

	private static String sanitizeHtml(String html, boolean allowImages) {
		var li = Safelist.relaxed();
		if (!allowImages) li.removeTags("img");
		return Jsoup.clean(html, li);
	}
	
}
