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

package com.unascribed.partyflow.handler.frontend;

import java.io.IOException;
import java.sql.SQLException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.data.QMeta;
import com.unascribed.partyflow.handler.util.MultipartData;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.Get;
import com.unascribed.partyflow.handler.util.SimpleHandler.MultipartPost;
import com.unascribed.partyflow.logic.ProseHelper;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.permission.Permission;
import com.unascribed.partyflow.util.Services;

public class AdminHandler extends SimpleHandler implements Get, MultipartPost {

	private static final Logger log = LoggerFactory.getLogger(AdminHandler.class);

	@Override
	public void get(String path, HttpServletRequest req, HttpServletResponse res)
			throws IOException, ServletException, SQLException {
		var s = SessionHelper.get(req)
				.assertPresent()
				.assertPermission(Permission.admin.administrate);
		
		var desc = QMeta.site_description.get();
		
		res.setStatus(HTTP_200_OK);
		MustacheHandler.serveTemplate(req, res, "admin.hbs.html", new Object() {
			String description = desc;
			String descriptionMd = Services.remark.convert(desc);
			String error = req.getParameter("error");
		});
	}

	@Override
	public void multipartPost(String path, HttpServletRequest req, HttpServletResponse res, MultipartData data)
			throws IOException, ServletException, SQLException {
		var session = SessionHelper.get(req)
				.assertPresent()
				.assertCsrf(data.getPartAsString("csrf", 64))
				.assertPermission(Permission.release.create);
		
		for (var v : QMeta.values()) {
			if (v == QMeta.site_description) continue;
			var d = data.getPartAsString(v.name(), 4096);
			if (d != null) {
				v.set(d);
			}
		}
		QMeta.site_description.set(ProseHelper.getSafeHtml(data, "site_description", true));
		
		res.sendRedirect(URLs.relative("admin"));
	}
	
}
