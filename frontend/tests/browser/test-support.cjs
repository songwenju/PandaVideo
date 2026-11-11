const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const outputDirectory = process.env.NAS_TEST_OUTPUT || path.join(os.tmpdir(), "pandavideo-selftest");
fs.mkdirSync(outputDirectory, { recursive: true });
const macChrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const browserPath = process.env.BROWSER_PATH || (fs.existsSync(macChrome) ? macChrome : null);

module.exports = {
  fixturePath: name => path.join(__dirname, "fixtures", name),
  outputPath: name => path.join(outputDirectory, name),
  launchOptions: { headless: true, ...(browserPath ? { executablePath: browserPath } : {}) },
};
