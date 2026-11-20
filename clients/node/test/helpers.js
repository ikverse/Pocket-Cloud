"use strict";
// Starts the server the app tests run against: the in-memory fake (test/fake-server.js) by default. Step 2 of
// docs/plan-v2.md also runs them against the real server; tests that use /_fake/ controls check `isFake`.

const fs = require("fs");
const os = require("os");
const path = require("path");
const { startFake } = require("./fake-server");
const pocket = require("..");

async function startServer() {
  const fake = await startFake();
  const homes = [];
  const env = {
    isFake: true,
    endpoint: fake.endpoint,
    secret: fake.secret,
    fake,
    /** A fresh machine (its own ~/.pocket), so each call is another device. */
    home() {
      const h = fs.mkdtempSync(path.join(os.tmpdir(), "pocket-node-app-"));
      homes.push(h);
      return h;
    },
    /** Open app on a new device (or on `home`'s device). */
    open(app, opts = {}) {
      return pocket.app(app, { endpoint: env.endpoint, enrollSecret: env.secret, home: opts.home || env.home(), deviceName: "Node test", ...opts });
    },
    tmp() {
      return env.home();
    },
    /** POST /_fake/<route> (fake only). */
    async control(route, body = {}, app) {
      const res = await fetch(`${env.endpoint}/_fake/${route}${app ? `?app=${encodeURIComponent(app)}` : ""}`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`fake ${route}: ${res.status} ${await res.text()}`);
      return res.json();
    },
    async requests() {
      return (await (await fetch(`${env.endpoint}/_fake/requests`)).json()).requests;
    },
    async notifications() {
      return (await (await fetch(`${env.endpoint}/_fake/notifications`)).json()).sent;
    },
    async close() {
      await fake.close();
      for (const h of homes) fs.rmSync(h, { recursive: true, force: true });
    },
  };
  return env;
}

module.exports = { startServer };
