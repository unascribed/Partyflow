package com.unascribed.partyflow;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

// For some reason this doesn't work in Groovy

public class TweakableGZIPOutputStream extends GZIPOutputStream {
	public TweakableGZIPOutputStream(OutputStream out) throws IOException {
		super(out);
	}

	public TweakableGZIPOutputStream level(int level) {
		this.def.setLevel(level);
		return this;
	}
}
