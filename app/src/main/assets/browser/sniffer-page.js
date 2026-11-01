/*
 * Hazel browser sniffer — part 1 of 2: element watching.
 *
 * Injected ahead of part 2. Finds media the page puts in the document: video and audio
 * elements, whatever they are currently playing, and any source children. A MutationObserver
 * keeps watching for players a framework adds after load, which is most of them.
 *
 * Part 2 handles media fetched by script, which names no element source at all. The halves
 * are separate files only so each stays small enough to read.
 *
 * Every callback is wrapped in try/catch on purpose: a throw from inside a page's own
 * script would break that page, and Hazel must never be the reason a site stops working.
 */
(function () {
  if (window.__hazel) return;

  var seen = {};

  function looksMedia(url, mime) {
    if (mime && (mime.indexOf('video/') === 0 || mime.indexOf('audio/') === 0)) return true;
    if (!url) return false;
    return /\.(m3u8|mpd|mp4|m4v|mov|webm|mkv|m4a|mp3|aac|flac|ogg|ts)([?#]|$)/i.test(url);
  }

  function report(url, mime, title) {
    try {
      if (!url || seen[url]) return;
      seen[url] = true;
      if (!window.HazelSniffer || !window.HazelSniffer.onMediaDetected) return;
      window.HazelSniffer.onMediaDetected(String(url), String(mime || ''), String(title || document.title || ''));
    } catch (e) {}
  }

  function grab(el) {
    try {
      var src = el.currentSrc || el.src || '';
      if (src) report(src, el.type || '', el.title || document.title);
      var sources = el.querySelectorAll ? el.querySelectorAll('source') : [];
      for (var i = 0; i < sources.length; i++) {
        report(sources[i].src, sources[i].type || '', el.title || document.title);
      }
    } catch (e) {}
  }

  function watch(el) {
    if (!el || el.__hazelWatched) return;
    el.__hazelWatched = true;
    var onEvent = function () { grab(el); };
    el.addEventListener('play', onEvent, true);
    el.addEventListener('loadstart', onEvent, true);
    el.addEventListener('loadedmetadata', onEvent, true);
    grab(el);
  }

  function scan() {
    try {
      var nodes = document.querySelectorAll('video, audio');
      for (var i = 0; i < nodes.length; i++) watch(nodes[i]);
    } catch (e) {}
  }

  window.__hazel = { looksMedia: looksMedia, report: report, grab: grab, scan: scan };

  try {
    var observer = new MutationObserver(function (mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes || [];
        for (var j = 0; j < added.length; j++) {
          var node = added[j];
          if (!node || node.nodeType !== 1) continue;
          if (node.tagName === 'VIDEO' || node.tagName === 'AUDIO') watch(node);
          else scan();
        }
      }
    });
    observer.observe(document.documentElement || document, { childList: true, subtree: true });
  } catch (e) {}

  scan();
})();
