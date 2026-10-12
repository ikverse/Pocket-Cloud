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

export class PocketError extends Error {}
