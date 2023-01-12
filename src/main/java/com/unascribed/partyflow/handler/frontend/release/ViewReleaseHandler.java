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
import java.util.List;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.overzealous.remark.Remark;
import com.unascribed.partyflow.config.TranscodeFormat;
import com.unascribed.partyflow.data.QReleases;
import com.unascribed.partyflow.data.QTracks;
import com.unascribed.partyflow.data.QTracks.Track;
import com.unascribed.partyflow.data.util.Queries;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.SessionHelper.Session;

public class ViewReleaseHandler extends SimpleHandler implements GetOrHead {

	private static final Logger log = LoggerFactory.getLogger(ViewReleaseHandler.class);
	private static final Gson gson = new Gson();
	
	private final Remark remark = new Remark(com.overzealous.remark.Options.github());
	
	private final String template;
	
	public ViewReleaseHandler(String template) {
		this.template = template;
	}

	@Override
	public void getOrHead(String slug, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		Map<String, String> query = parseQuery(req);
		Session s = SessionHelper.getSession(req);
		try (var c = Queries.begin()) {
			var releaseOpt = QReleases.get(s, slug);
			if (releaseOpt.isPresent()) {
				var r = releaseOpt.get();
				var _tracks = QTracks.get(r.releaseId());
				JsonArray _tracksJson = new JsonArray();
				long durAccum = 0;
				for (var t : _tracks) {
					JsonObject obj = new JsonObject();
					obj.addProperty("title", t.title());
					obj.addProperty("subtitle", t.subtitle());
					obj.addProperty("slug", t.slug());
					obj.addProperty("art", t.art());
					if (t.trackNumber().isPresent())
						obj.addProperty("trackNumber", t.trackNumber().getAsInt());
					obj.addProperty("start", durAccum/48000D);
					durAccum += t.duration();
					obj.addProperty("end", durAccum/48000D);
					_tracksJson.add(obj);
				}
				res.setStatus(HTTP_200_OK);
				boolean _editable = s != null && r.userId() == s.userId();
				String desc = r.description();
				MustacheHandler.serveTemplate(req, res, template, r, new Object() {
					boolean editable = _editable;
					String descriptionMd = remark.convert(desc);
					String error = query.get("error");
					String albumArt = r.art();
					String albumTitle = r.title();
					List<Track> tracks = _tracks;
					double albumLoudness = r.loudness().isPresent() ? r.loudness().getAsInt()/10D : 0;
					boolean has_tracks = !_tracks.isEmpty();
					List<Object> download_formats = TranscodeFormat.enumerate(tf -> tf.usage().canDownload());
					List<Object> stream_formats = TranscodeFormat.enumerate(tf -> tf.usage().canStream());
					String stream_formats_json = gson.toJson(TranscodeFormat.enumerateAsJson(tf -> tf.usage().canStream()));
					String tracks_json = gson.toJson(_tracksJson);
					boolean doneProcessing = r.concatMaster() != null;
				});
			} else {
				res.sendError(HTTP_404_NOT_FOUND);
			}
		}
	}

}
