import { S3Client, S3ClientConfig } from "@aws-sdk/client-s3";

export interface ConnectOptions {
  /** Server address; otherwise POCKET_ENDPOINT or ~/.pocket/config. */
  endpoint?: string;
  /** Enrollment secret; otherwise POCKET_ENROLL_SECRET or ~/.pocket/config. */
  enrollSecret?: string;
  /** Fixed device id (8-64 letters, digits, dashes); otherwise POCKET_DEVICE_ID or a saved random id. */
  deviceId?: string;
  /** Name shown in the control panel; otherwise POCKET_DEVICE_NAME or the host name. */
  deviceName?: string;
  /** Folder for the machine's Pocket files; default ~/.pocket. */
  home?: string;
  /** Extra S3Client settings. */
  clientConfig?: Partial<S3ClientConfig>;
}

/** Connect an app and get a ready S3Client for its "shared" and "device" buckets. */
export function connect(app: string, opts?: ConnectOptions): Promise<S3Client>;

// ---------- pocket.app(): the plain /_pocket/ routes (docs/app-api.md section 10) ----------

export interface AppOptions {
  /** Server address; otherwise POCKET_ENDPOINT or ~/.pocket/config. */
  endpoint?: string;
  /** Enrollment secret; otherwise POCKET_ENROLL_SECRET or ~/.pocket/config. */
  enrollSecret?: string;
  /** Fixed device id (8-64 letters, digits, dashes); otherwise POCKET_DEVICE_ID or a saved random id. */
  deviceId?: string;
  /** Name shown in the control panel and in sync conflict names; otherwise POCKET_DEVICE_NAME or the host name. */
  deviceName?: string;
  /** Folder for the machine's Pocket files; default ~/.pocket. */
  home?: string;
  /** Time to wait for an answer's headers, default 30000. */
  timeoutMs?: number;
}

export type Bucket = "shared" | "device" | "family" | (string & {});

export interface Entry {
  bucket: string;
  key: string;
  /** null for folder entries of list({folders: true}) */
  size: number | null;
  /** Quoted, opaque: compare for equality only. */
  etag: string | null;
  /** Unix seconds. */
  modified: number | null;
}

export interface Change {
  seq: number;
  bucket: string;
  key: string;
  kind: "put" | "delete" | "move";
  /** move only */
  from?: { bucket: string; key: string };
  size: number | null;
  etag: string | null;
  time: number;
  device: string | null;
  source: "app" | "panel" | "retention" | "undo" | "card" | (string & {});
}

export interface ReleaseFile {
  name: string;
  /** Relative to the publish folder, e.g. "5.9.1/app-5.9.1.apk". */
  path: string;
  size: number;
  sha256: string;
  platform: string | null;
  /** Key-less URL when the publish folder is on, else null. */
  url: string | null;
}

export interface Release {
  app: string;
  version: string;
  published: number;
  notes: string;
  files: ReleaseFile[];
  /**
   * Fetch a file and check its size and sha256 (ChecksumMismatch otherwise). dest: a file, or an existing
   * folder to save into. With several files, name says which. Resolves to the saved path.
   */
  download(dest: string, opts?: { name?: string }): Promise<string>;
}

export interface Share {
  token: string;
  url: string;
  public: boolean;
  bucket: string;
  key: string;
  created: number;
  expires: number;
  downloads: number;
  max_downloads: number | null;
  has_password: boolean;
  [k: string]: unknown;
}

export interface SyncResult {
  uploaded: string[];
  downloaded: string[];
  deletedLocal: string[];
  deletedRemote: string[];
  /** Pairs: the key kept, then the key of the uploaded conflict copy. */
  conflicts: string[];
}

export interface AppInfo {
  app: string;
  device_id: string;
  buckets: string[];
  features: string[];
  publish: { enabled: boolean; url: string | null };
  notify: { enabled: boolean; topic: string; url: string | null };
  push: { topic: string; url: string } | null;
  retention: { bucket: string; prefix: string; keep_last: number | null; keep_days: number | null }[];
  changes: { keep_days: number };
  read_only: boolean;
}

export interface WriteOptions {
  bucket?: Bucket;
  /** Write only if the current file has this ETag. */
  ifMatch?: string;
  /** "*": write only if there is no file yet. */
  ifNoneMatch?: "*";
  contentType?: string;
}

export interface ChangeFeed extends AsyncIterable<Change> {
  /** The cursor to carry on from; after iterating, the one for the next call. */
  readonly cursor: string | null;
}

export interface ChangeHandle {
  /** Resolves once the feed is being followed. */
  readonly ready: Promise<void>;
  readonly cursor: string | null;
  stop(): void;
}

export interface PocketApp {
  readonly name: string;
  /** Server features from /_pocket/health; null if the server could not be reached when opening. */
  readonly features: Set<string> | null;

  info(opts?: { fresh?: boolean }): Promise<AppInfo>;

  get(key: string, opts?: { bucket?: Bucket }): Promise<Buffer>;
  getFile(key: string, dest: string, opts?: { bucket?: Bucket }): Promise<Entry>;
  put(key: string, data: Buffer | Uint8Array | string, opts?: WriteOptions): Promise<Entry>;
  /** Streams the file and keeps its modification time. Up to 2 GiB; bigger files go through connect(). */
  putFile(key: string, src: string, opts?: WriteOptions): Promise<Entry>;
  stat(key: string, opts?: { bucket?: Bucket }): Promise<Entry | null>;
  list(prefix?: string, opts?: { bucket?: Bucket; folders?: boolean; limit?: number }): AsyncIterable<Entry>;
  delete(key: string, opts?: { bucket?: Bucket; ifMatch?: string }): Promise<void>;
  move(src: string, dst: string, opts?: { bucket?: Bucket; toBucket?: Bucket; overwrite?: boolean; ifMatch?: string }): Promise<Entry>;
  getJson<T = unknown>(key: string, opts?: { bucket?: Bucket; default?: T }): Promise<T>;
  putJson(key: string, value: unknown, opts?: Omit<WriteOptions, "contentType">): Promise<Entry>;

  /** Upload to <prefix><name>/<UTC yyyymmdd-HHMMSS>-<name>, then keep only the `keep` newest there. */
  backup(path: string, opts?: { keep?: number; prefix?: string; bucket?: Bucket }): Promise<Entry>;
  restoreLatest(prefix: string, dest: string, opts?: { bucket?: Bucket }): Promise<Entry | null>;
  /** Two-way sync; throws PocketError code "MassDelete" (with .count, .result) above 50 deletes. */
  sync(folder: string, opts?: { bucket?: Bucket; prefix?: string; allowMassDelete?: boolean }): Promise<SyncResult>;

  /** Changes after `since`, all pages. Without since: none, and .cursor is "now". */
  changes(opts?: { since?: string | null; wait?: number; limit?: number }): ChangeFeed;
  /** Follow the feed in the background: the push topic when there is one, else long poll. */
  onChange(
    cb: (change: Change) => void | Promise<void>,
    opts?: {
      since?: string | null;
      onError?: (err: Error) => void;
      /** After CursorExpired: list what you follow again; the feed then carries on from `cursor`. */
      onResync?: (cursor: string) => void | Promise<void>;
      /** false: long poll even when there is a push topic. */
      push?: boolean;
    }
  ): ChangeHandle;

  /**
   * Upload `file` as release `version` and point releases/latest.json at it. extra: electron-builder's
   * latest*.yml (to the top of releases/, its url/path pointed at <version>/), .blockmap files (next to the
   * installer), and any other file to put at the top of releases/.
   */
  publish(file: string, version: string, opts?: { notes?: string; platform?: string; extra?: string[] }): Promise<Release>;
  releases(): { latest(opts?: { platform?: string }): Promise<Release | null> };
  /** The key-less publish URL, for electron-updater's generic provider; null when the server has none. */
  releasesUrl(): Promise<string | null>;

  notify(
    message: string,
    opts?: { title?: string; tags?: string[]; priority?: 1 | 2 | 3 | 4 | 5; click?: string }
  ): Promise<{ ok: true; id: string; topic: string }>;
  /** expires in seconds, default 86400. */
  share(key: string, opts?: { expires?: number; bucket?: Bucket; password?: string; maxDownloads?: number }): Promise<Share>;
  quota(): Promise<Record<string, unknown>>;
}

/** Open an app on the plain /_pocket/ routes (enrolls on first use). */
export function app(name: string, opts?: AppOptions): Promise<PocketApp>;

// ---------- errors ----------

export class PocketError extends Error {
  /** The server's "error" code, or a library code: FeatureMissing, MassDelete, ChecksumMismatch, NotConfigured, Unreachable. */
  code: string;
  /** HTTP status, 0 when there was no answer. */
  status: number;
}
export class NotFound extends PocketError {}
export class PreconditionFailed extends PocketError {}
export class QuotaExceeded extends PocketError {}
export class ReadOnly extends PocketError {}
export class AccessDenied extends PocketError {}
export class CursorExpired extends PocketError {
  /** Where to carry on after a full resync. */
  cursor: string | null;
}
export class RateLimited extends PocketError {
  retryAfter: number | null;
}
export class FeatureOff extends PocketError {}
export class Unreachable extends PocketError {}
