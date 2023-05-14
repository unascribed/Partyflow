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

package com.unascribed.partyflow.handler.frontend.release;

import java.io.IOException;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.QGeneric;
import com.unascribed.partyflow.handler.frontend.CreateReleaseHandler;
import com.unascribed.partyflow.handler.util.MultipartData;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.MultipartPost;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;
import com.google.common.base.Strings;

public class EditReleaseHandler extends SimpleHandler implements GetOrHead, MultipartPost {

	private static final Logger log = LoggerFactory.getLogger(EditReleaseHandler.class);
	
	@Override
	public void getOrHead(String slug, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		res.sendRedirect(URLs.url("release/"+escPathSeg(slug)+keepQuery(req)));
	}
	
	@Override
	public void multipartPost(String slugs, HttpServletRequest req, HttpServletResponse res, MultipartData data)
			throws IOException, ServletException, SQLException {
		var s = SessionHelper.get(req)
				.assertPresent()
				.assertCsrf(data.getPartAsString("csrf", 64));
		
		Part art = data.getPart("art");
		String title = Strings.nullToEmpty(data.getPartAsString("title", 1024));
		String subtitle = Strings.nullToEmpty(data.getPartAsString("subtitle", 1024));
		String descriptionMd = data.getPartAsString("descriptionMd", 65536);
		String description;
		if (descriptionMd != null) {
			Parser parser = Parser.builder().build();
			HtmlRenderer rend = HtmlRenderer.builder().build();
			description = sanitizeHtml(rend.render(parser.parse(descriptionMd)));
		} else {
			description = sanitizeHtml(Strings.nullToEmpty(data.getPartAsString("description", 65536)));
		}
		if (title.trim().isEmpty()) {
			res.sendRedirect(URLs.url("release/"+escPathSeg(slugs)+"?error=Title is required"));
			return;
		}
		if (title.length() > 255) {
			res.sendRedirect(URLs.url("release/"+escPathSeg(slugs)+"?error=Title is too long"));
			return;
		}
		if (subtitle.length() > 255) {
			res.sendRedirect(URLs.url("release/"+escPathSeg(slugs)+"?error=Subtitle is too long"));
			return;
		}
		if (description.length() > 16384) {
			res.sendRedirect(URLs.url("release/"+escPathSeg(slugs)+"?error=Description is too long"));
			return;
		}
		String artPath = null;
		if (art != null && art.getSize() > 4) {
			try {
				artPath = CreateReleaseHandler.processArt(art);
			} catch (IllegalArgumentException e) {
				res.sendRedirect(URLs.url("release/"+escPathSeg(slugs)+"?error="+URLEncoder.encode(e.getMessage(), "UTF-8")));
				return;
			}
		}
		try (Connection c = Partyflow.sql.getConnection()) {
			boolean published;
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT `published` FROM `releases` "
					+ "WHERE `slug` = ? AND `user_id` = ?;")) {
				ps.setString(1, slugs);
				ps.setInt(2, s.userId());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.first()) {
						published = rs.getBoolean("published");
					} else {
						res.sendRedirect(URLs.url("release/"+escPathSeg(slugs)+"?error=You're not allowed to do that"));
						return;
					}
				}
			}
			String slug;
			if (published) {
				slug = slugs;
			} else {
				slug = QGeneric.findSlug("releases", Partyflow.sanitizeSlug(title));
			}
			String midfix = data.getPart("publish") != null ? ", `published` = true, `published_at` = NOW()" : "";
			String extraCols = artPath != null ? "`art` = ?," : "";
			try (PreparedStatement ps = c.prepareStatement(
					"UPDATE `releases` SET `title` = ?, `subtitle` = ?, `slug` = ?, "+extraCols+" `description` = ?, `last_updated` = NOW()"+midfix
					+ " WHERE `slug` = ? AND `user_id` = ?;")) {
				int i = 1;
				ps.setString(i++, title);
				ps.setString(i++, subtitle);
				ps.setString(i++, slug);
				if (artPath != null) {
					ps.setString(i++, artPath);
				}
				ps.setString(i++, description);
				ps.setString(i++, slugs);
				ps.setInt(i++, s.userId());
				ps.executeUpdate();
			}
			if (data.getPart("addTrack") != null) {
				res.sendRedirect(URLs.url("release/"+escPathSeg(slug)+"/add-track"));
			} else {
				res.sendRedirect(URLs.url("release/"+escPathSeg(slug)));
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}
	
	private String sanitizeHtml(String html) {
		return Jsoup.clean(html, Safelist.relaxed().removeTags("img"));
	}

}
