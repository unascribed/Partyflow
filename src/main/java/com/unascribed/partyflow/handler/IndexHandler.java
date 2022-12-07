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

package com.unascribed.partyflow.handler;

import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.data.QMeta;

import java.io.IOException;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.SimpleHandler;

public class IndexHandler extends SimpleHandler implements GetOrHead {

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head) throws IOException, ServletException, SQLException {
		var desc = QMeta.getSiteDescription();
		MustacheHandler.serveTemplate(req, res, "index.hbs.html", new Object() {
			String site_description = desc;
		});
	}

}
