// Split into two files so each stays small enough to read and review.
// The browser injects them in this order:
//
//   1. sniffer-page.js     - watches <video>/<audio> elements
//   2. sniffer-network.js  - hooks fetch, XMLHttpRequest and MediaSource
//
// Nothing loads this file; it exists only to say where the parts went.
