/*
 * Hazel browser sniffer — part 2 of 2: scripted media.
 *
 * Loaded after part 1. Handles media the page fetches by script, which names no element
 * source at all and is the only way a hand-rolled player is caught. Covers fetch,
 * XMLHttpRequest, and MediaSource, which is what a page uses when it feeds a video element
 * with segments it fetched itself.
 *
 * Part 1 owns the shared helpers on window.__hazel; this half only uses them. It also owns
 * the decision about what counts as media, including the fragments it deliberately skips,
 * so there is one definition rather than two that can drift.
 */
(function () {
  if (!window.__hazel || window.__hazelNetwork) return;
  window.__hazelNetwork = true;

  var report = window.__hazel.report;
  var grab = window.__hazel.grab;
  var looksMedia = window.__hazel.looksMedia;

  var originalFetch = window.fetch;
  if (originalFetch) {
    window.fetch = function () {
      try {
        var first = arguments[0];
        var target = (first && first.url) ? first.url : first;
        if (typeof target === 'string' && looksMedia(target, '')) report(target, '', document.title);
      } catch (e) {}
      return originalFetch.apply(this, arguments);
    };
  }

  var originalOpen = window.XMLHttpRequest && XMLHttpRequest.prototype.open;
  if (originalOpen) {
    XMLHttpRequest.prototype.open = function (method, url) {
      try {
        if (typeof url === 'string' && looksMedia(url, '')) report(url, '', document.title);
      } catch (e) {}
      return originalOpen.apply(this, arguments);
    };
  }

  if (window.MediaSource && MediaSource.prototype.addSourceBuffer) {
    var originalAdd = MediaSource.prototype.addSourceBuffer;
    MediaSource.prototype.addSourceBuffer = function () {
      try {
        var candidates = document.querySelectorAll('video, audio');
        for (var i = 0; i < candidates.length; i++) grab(candidates[i]);
      } catch (e) {}
      return originalAdd.apply(this, arguments);
    };
  }

  try {
    var entries = (performance.getEntriesByType ? performance.getEntriesByType('resource') : []) || [];
    for (var k = 0; k < entries.length; k++) {
      if (looksMedia(entries[k].name, '')) report(entries[k].name, '', document.title);
    }
  } catch (e) {}

  if (window.__hazel.scan) window.__hazel.scan();
})();
