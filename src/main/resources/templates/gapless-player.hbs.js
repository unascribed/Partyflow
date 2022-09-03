(async function() {
	document.body.classList.add("yesscript");
	const data = document.currentScript.dataset;
	const loudness = Number(data.loudness);
	const formats = JSON.parse(data.formats);
	const tracks = JSON.parse(data.tracks);
	if (tracks.length === 0) return;
	const firstTrack = tracks[0];
	const release = data.releaseSlug;
	tracks.forEach((track, i) => {
		let ele = Array.prototype.slice.apply(document.querySelectorAll(".track")).find(e => e.dataset.trackSlug === track.slug);
		track.element = ele;
		track.button = ele.querySelector(".player-control");
		track.length = track.end-track.start;
		track.index = i;
	});
	const audio = new Audio();
	let selectedFormat = null;
	for (let i = 0; i < formats.length; i++) {
		const fmt = formats[i];
		if (audio.canPlayType(fmt.mime) !== "") {
			console.debug("Trying "+fmt.name+"...");
			try {
				await new Promise((resolve, reject) => {
					const audio = new Audio();
					// do decode testing with a track rather than the gapless file, as it's faster
					audio.src = "{{root}}transcode/track/"+firstTrack.slug+"?format="+fmt.name;
					audio.addEventListener("canplay", () => {
						resolve(audio);
					});
					audio.addEventListener("error", (e) => {
						reject(e);
					});
					audio.load();
				});
				selectedFormat = fmt;
				console.info("Successfully loaded "+fmt.name);
				break;
			} catch (e) {
				console.error("Can't play media format "+fmt.name, e);
			}
		}
	}
	function trans(e, from, to, cb) {
		if (!e.classList.contains(from)) return;
		e.classList.add("trans");
		setTimeout(() => {
			e.classList.remove(from);
			e.classList.add(to);
			e.classList.remove("trans");
			if (cb) cb();
		}, 200);
	}
	const widget = document.querySelector("#player-widget");
	const seekbar = widget.querySelector(".seekbar");
	const seekbarPosition = seekbar.querySelector(".position");
	const seekbarBuffered = seekbar.querySelector(".buffered");
	const volbar = widget.querySelector(".volumebar");
	const volbarPosition = volbar.querySelector(".position");
	const volicon = widget.querySelector(".volicon");
	const globalPlayPause = widget.querySelector(".play-toggle");
	const skipPrev = widget.querySelector(".skip-prev");
	const skipNext = widget.querySelector(".skip-next");
	let currentTrack = firstTrack;
	let mouseDownInSeekbar = false;
	let mouseDownInVolbar = false;
	function dragSeekbar(x) {
		let bcr = seekbar.getBoundingClientRect();
		x -= bcr.left;
		let p = x/bcr.width;
		if (p < 0) p = 0;
		if (p > 1) p = 1;
		audio.currentTime = currentTrack.start+(currentTrack.length*p);
	}
	function dragVolbar(x) {
		let bcr = volbar.getBoundingClientRect();
		x -= bcr.left;
		let p = x/bcr.width;
		if (p < 0) p = 0;
		if (p > 1) p = 1;
		audio.volume = p;
	}
	window.addEventListener("mouseup", () => {
		if (mouseDownInSeekbar) audio.play();
		mouseDownInSeekbar = false;
		mouseDownInVolbar = false;
	});
	seekbar.addEventListener("mousedown", (e) => {
		audio.pause();
		mouseDownInSeekbar = true;
		dragSeekbar(e.clientX);
	});
	volbar.addEventListener("mousedown", (e) => {
		mouseDownInVolbar = true;
		dragVolbar(e.clientX);
	});
	window.addEventListener("mousemove", (e) => {
		if (mouseDownInSeekbar) dragSeekbar(e.clientX);
		if (mouseDownInVolbar) dragVolbar(e.clientX);
	});
	skipPrev.addEventListener("click", () => {
		if (currentTrack.index > 0) {
			audio.currentTime = tracks[currentTrack.index-1].start;
		}
	});
	skipNext.addEventListener("click", () => {
		if (currentTrack.index < tracks.length) {
			audio.currentTime = tracks[currentTrack.index+1].start;
			updateTime();
		}
	});
	updateSkipState();
	globalPlayPause.addEventListener("click", (e) => {
		if (audio.paused) {
			audio.play();
		} else {
			audio.pause();
		}
	});
	tracks.forEach((track) => {
		track.button.addEventListener("click", (e) => {
			let paused = audio.paused;
			if (currentTrack.slug !== track.slug) {
				audio.currentTime = track.start;
				updateTime();
				paused = true;
			}
			if (paused) {
				audio.play();
			} else {
				audio.pause();
			}
			e.preventDefault();
		});
	});
	audio.src = "{{root}}transcode/release/"+release+"?format="+selectedFormat.name;
	function updateBuffered() {
		if (audio.buffered.length > 0 && currentTrack !== null) {
			let end = audio.buffered.end(audio.buffered.length-1);
			let progress = (end-currentTrack.start)/currentTrack.length;
			if (progress < 0) progress = 0;
			if (progress > 1) progress = 1;
			seekbarBuffered.style.width = (progress*100)+"%";
		}
	}
	function set(e, clazz, v) {
		if (v) {
			e.classList.add(clazz);
		} else {
			e.classList.remove(clazz);
		}
	}
	function updateSkipState() {
		set(skipPrev, "disabled", currentTrack.index === 0);
		set(skipNext, "disabled", currentTrack.index === tracks.length-1);
	}
	function updateTime() {
		let track = tracks.find((track) => track.start < audio.currentTime && track.end > audio.currentTime);
		if (!track) return; // ????
		if (!currentTrack || track.slug !== currentTrack.slug) {
			if (currentTrack) {
				trans(currentTrack.button, "pause", "play");
			}
			currentTrack = track;
			trans(currentTrack.button, "play", "pause");
			updateSkipState();
		}
		let progress = (audio.currentTime-track.start)/track.length;
		seekbarPosition.style.width = (progress*100)+"%";
		updateBuffered();
	}
	function updateVolume() {
		let v = audio.volume;
		let clazz = "high";
		if (v <= 0)  {
			clazz = "muted";
		} else if (v <= 1/3) {
			clazz = "low";
		} else if (v <= 2/3) {
			clazz = "medium";
		}
		volicon.classList.remove("high");
		volicon.classList.remove("medium");
		volicon.classList.remove("low");
		volicon.classList.remove("muted");
		volicon.classList.add(clazz);
		volbarPosition.style.width = (v*100)+"%";
	}
	audio.addEventListener("progress", updateBuffered);
	audio.addEventListener("timeupdate", updateTime);
	audio.addEventListener("canplay", () => {
		trans(globalPlayPause, "loading", "play");
	});
	audio.addEventListener("error", (e) => {
		console.error("Can't play", e);
	});
	audio.addEventListener("volumechange", updateVolume);
	audio.addEventListener("pause", (e) => {
		if (mouseDownInSeekbar) return;
		trans(globalPlayPause, "pause", "play");
		if (currentTrack) trans(currentTrack.button, "pause", "play");
	});
	audio.addEventListener("play", (e) => {
		if (mouseDownInSeekbar) return;
		trans(globalPlayPause, "play", "pause");
		if (currentTrack) trans(currentTrack.button, "play", "pause");
	});
	updateVolume();
	audio.load();
})();
