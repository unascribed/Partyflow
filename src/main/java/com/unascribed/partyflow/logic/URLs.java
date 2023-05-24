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

import javax.annotation.Nullable;

import com.unascribed.partyflow.Partyflow;

/**
 * The Earl of URLs
 */
public class URLs {

	private static String blobPattern;
	private static String absBlobPattern;

	/**
	 * Delegates to either {@link #absolute(String)} or {@link #relative(String)} depending on the value of
	 * {@code abs}. The path must not start with a /.
	 */
	public static String url(boolean abs, String path) {
		return abs ? absolute(path) : relative(path);
	}

	/**
	 * Returns the "absolute" URL for the given path in the Partyflow application, based on the
	 * configuration. This will be a fully qualified URL with a scheme and host.  The path must not
	 * start with a /.
	 * <p>
	 * For example, {@code api/v1/whoami} could become {@code https://example.com/partyflow/api/v1/whoami}.
	 */
	public static String absolute(String path) {
		return absoluteRoot()+path;
	}

	/**
	 * Returns the relative URL for the given path in the Partyflow application, based on the
	 * configuration. This will start with a / and is relative to the domain. The path must not
	 * start with a /.
	 * <p>
	 * For example, {@code api/v1/whoami} could become {@code /partyflow/api/v1/whoami}.
	 */
	public static String relative(String path) {
		return root()+path;
	}

	/**
	 * Returns the "public root" of the Partyflow application, based on the configuration. This will
	 * be a fully qualified URL with a scheme and host.
	 */
	public static String absoluteRoot() {
		return Partyflow.config.http.publicUrl+root();
	}

	/**
	 * Returns the "relative root" of the Partyflow application, based on the configuration. This
	 * will start with a / and is relative to the domain.
	 */
	public static String root() {
		return Partyflow.config.http.path;
	}
	
	
	public static void init() {
		if (Partyflow.config.storage.publicUrlPattern.startsWith("/")) {
			blobPattern = Partyflow.config.storage.publicUrlPattern;
			absBlobPattern = Partyflow.config.http.publicUrl+Partyflow.config.storage.publicUrlPattern;
		} else if (Partyflow.config.storage.publicUrlPattern.startsWith("http://") || Partyflow.config.storage.publicUrlPattern.startsWith("https://")) {
			blobPattern = absBlobPattern = Partyflow.config.storage.publicUrlPattern;
		} else {
			blobPattern = relative(Partyflow.config.storage.publicUrlPattern);
			absBlobPattern = absolute(Partyflow.config.storage.publicUrlPattern);
		}
	}

	/**
	 * Resolve a blob path based on the storage configuration. This <i>may or may not</i> return an
	 * absolute URL with a scheme and host, depending on the config.
	 * <p>
	 * For example, these are both possible resolutions for "art/foobar.jpeg":<br>
	 * {@code /files/art/foobar.jpeg}<br>
	 * {@code https://s3.example.com/us-central-7/art/foobar.jpeg}
	 */
	public static String blob(String blob) {
		return _resolveBlob(blob, false);
	}

	/**
	 * Resolve a blob path based on the storage configuration. This <i>will always</i> return an
	 * absolute URL with a scheme and host.
	 * <p>
	 * For example, these are both possible resolutions for "art/foobar.jpeg":<br>
	 * {@code https://example.com/partyflow/files/art/foobar.jpeg}<br>
	 * {@code https://s3.example.com/us-central-7/art/foobar.jpeg}
	 */
	public static String absoluteBlob(String blob) {
		return _resolveBlob(blob, true);
	}

	private static String _resolveBlob(String blob, boolean abs) {
		return (abs ? absBlobPattern : blobPattern).replace("{}", blob);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * @see #blob
	 */
	public static String art(@Nullable String art) {
		return _resolveArt(art, "", false);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * The blob will have "-thumb.webp" appended, to retrieve the thumbnail instead of the full
	 * copy.
	 * @see #blob
	 */
	public static String artThumb(@Nullable String art) {
		return _resolveArt(art, "-thumb.webp", false);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * @see #absoluteBlob
	 */
	public static String absoluteArt(@Nullable String art) {
		return _resolveArt(art, "", true);
	}

	/**
	 * Resolve a blob path, returning the path to the static default art if {@code art} is null.
	 * The blob will have "-thumb.webp" appended, to retrieve the thumbnail instead of the full
	 * copy.
	 * @see #absoluteBlob
	 */
	public static String absoluteArtThumb(@Nullable String art) {
		return _resolveArt(art, "-thumb.webp", true);
	}

	private static String _resolveArt(String art, String suffix, boolean abs) {
		if (art == null) {
			return url(abs, "static/default_art.svg");
		} else {
			return blob(art+suffix);
		}
	}

}
