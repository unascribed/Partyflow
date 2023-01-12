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
import java.sql.SQLException;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.data.QReleases;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.UrlEncodedPost;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.SessionHelper.Session;

public class UnpublishReleaseHandler extends SimpleHandler implements GetOrHead, UrlEncodedPost {

	private static final Logger log = LoggerFactory.getLogger(UnpublishReleaseHandler.class);
	
	@Override
	public void getOrHead(String slug, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		res.sendRedirect(URLs.url("release/"+escPathSeg(slug)+keepQuery(req)));
	}

	@Override
	public void urlEncodedPost(String slug, HttpServletRequest req, HttpServletResponse res, Map<String, String> params)
			throws IOException, ServletException, SQLException {
		Session s = SessionHelper.getSessionOrThrow(req, params.get("csrf"));
		if (QReleases.publish(slug, s.userId(), false)) {
			res.sendRedirect(URLs.url("release/"+escPathSeg(slug)));
		} else {
			res.sendRedirect(URLs.url("release/"+escPathSeg(slug)+"?error=You're not allowed to do that"));
		}
	}

}
