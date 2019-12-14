package com.unascribed.partyflow.handler;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SimpleHandler.UrlEncodedPost;

public class LogoutHandler extends SimpleHandler implements UrlEncodedPost {

	@Override
	public void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException {
		String csrf = params.get("csrf");
		if (!Partyflow.isCsrfTokenValid(csrf)) {
			res.sendRedirect(Partyflow.config.http.path);
			return;
		}
		Session session = SessionHelper.getSession(req);
		if (session == null) {
			res.sendRedirect(Partyflow.config.http.path);
			return;
		}
		try (Connection c = Partyflow.sql.getConnection()) {
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE session_id = ?;")) {
				ps.setString(1, session.sessionId.toString());
				System.out.println(ps.executeUpdate());
			}
			SessionHelper.clearSessionCookie(res);
			res.sendRedirect(Partyflow.config.http.path);
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

}
