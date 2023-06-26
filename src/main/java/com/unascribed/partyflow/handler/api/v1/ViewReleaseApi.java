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

package com.unascribed.partyflow.handler.api.v1;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.OptionalInt;

import javax.annotation.Nullable;

import com.unascribed.partyflow.data.QReleases.FullRelease;
import com.unascribed.partyflow.data.QTracks.Track;
import com.unascribed.partyflow.data.util.QBase;
import com.unascribed.partyflow.data.QReleases;
import com.unascribed.partyflow.data.QTracks;
import com.unascribed.partyflow.handler.util.ApiHandler;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.util.SamplesUnit;
import com.unascribed.partyflow.logic.SessionHelper.Session;

public class ViewReleaseApi extends ApiHandler {

	public record RGData(double reference, double loudness, double peak) {
		public static RGData fromDb(OptionalInt loudness, OptionalInt peak) {
			if (!loudness.isPresent() || !peak.isPresent()) return null;
			double ref = -18;
			return new RGData(ref, ref-(loudness.getAsInt()/10D), Math.pow(10, peak.getAsInt()/200D));
		}
	}
	
	public record ArtData(String full, String thumb) {
		public static ArtData fromDb(String id) { return new ArtData(URLs.absoluteArt(id), URLs.absoluteArtThumb(id)); }
	}
	
	public record CreatorData(String username, String name, URLData url) {}
	
	public record URLData(String api, String frontend) {
		public static URLData fromSlug(String kind, String slug) {
			return new URLData(URLs.absolute("api/v1/"+kind+"/"+slug), URLs.absolute(kind+"/"+slug));
		}
	}
	
	public record TrackResponse(
			String slug, String title, String subtitle, String description, String lyrics,
			String duration, OptionalInt trackNumber,
			
			RGData replaygain, ArtData art,
			URLData url
			) {}
	
	public record ReleaseResponse(
			String slug, String title, String subtitle, CreatorData creator, String description, boolean published,
			RGData replaygain, ArtData art,
			
			Date publishedAt, Date createdAt, Date lastUpdated,
			
			List<TrackResponse> tracks,
			URLData url
		) {
		public static ReleaseResponse from(FullRelease r, @Nullable List<Track> tracks) {
			var releaseArt = ArtData.fromDb(r.artId());
			return new ReleaseResponse(r.slug(), r.title(), r.subtitle(),
					new CreatorData(r.creatorUsername(), r.creator(), URLData.fromSlug("user", r.creatorUsername())),
					r.description(), r.published(),
					RGData.fromDb(r.loudness(), r.peak()), releaseArt,
					r.publishedAt(), r.createdAt(), r.lastUpdated(),
					tracks == null ? null : tracks.stream()
						.map(t -> new TrackResponse(t.slug(), t.title(), t.subtitle(), t.description(), t.lyrics(),
								Duration.of(t.duration(), SamplesUnit.INST).toString(), t.trackNumber(), RGData.fromDb(t.loudness(), t.peak()),
								t.artId() == null ? releaseArt : ArtData.fromDb(t.artId()),
								URLData.fromSlug("track", t.slug())))
						.toList(),
					URLData.fromSlug("release", r.slug()));
		}
	}
	
	@GET
	public static ReleaseResponse invoke(Session session, @RequestPath String slug, @Nullable Boolean includeTracks)
			throws UserVisibleException, SQLException {
		try (var c = QBase.begin()) {
			var releaseOpt = QReleases.get(session, slug);
			if (releaseOpt.isPresent()) {
				var r = releaseOpt.get();
				var tracks = includeTracks == null || includeTracks ? QTracks.get(r.releaseId()) : null;
				return ReleaseResponse.from(r, tracks);
			} else {
				throw new UserVisibleException(404);
			}
		}
	}
	
}
