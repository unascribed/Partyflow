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
import java.util.List;
import javax.annotation.Nullable;

import com.unascribed.partyflow.data.QReleases;
import com.unascribed.partyflow.data.util.QBase;
import com.unascribed.partyflow.handler.api.v1.ViewReleaseApi.ReleaseResponse;
import com.unascribed.partyflow.handler.util.ApiHandler;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.SessionHelper.Session;

public class ReleasesApi extends ApiHandler {
	
	public record ReleasesResponse(List<ReleaseResponse> items, String prev, String next) {}
	
	@GET
	public static ReleasesResponse invoke(Session session, @RequestPath String slug, @Nullable Integer page, @Nullable Integer limit)
			throws UserVisibleException, SQLException {
		if (limit == null) {
			limit = 20;
		}
		if (limit > 50) {
			limit = 50;
		}
		if (page == null) {
			page = 1;
		}
		if (limit == 0)
			return new ReleasesResponse(List.of(), null, URLs.absolute("api/v1/releases?page="+(page+1)+"&limit=0"));
		try (var c = QBase.begin()) {
			var li = QReleases.getAll(session, limit, page);
			boolean more = li.size() == limit+1;
			if (more) li = li.subList(0, li.size()-1);
			return new ReleasesResponse(li.stream().map(r -> ReleaseResponse.from(r, null)).toList(),
					page > 1 ? URLs.absolute("api/v1/releases?page="+(page-1)+"&limit="+limit) : null,
					more ? URLs.absolute("api/v1/releases?page="+(page+1)+"&limit="+limit) : null);
		}
	}
	
}
