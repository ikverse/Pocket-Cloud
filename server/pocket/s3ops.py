"""Small S3 protocol helpers: naming the operation of a request, error bodies, and the DeleteObjects XML."""

import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

BUCKETS = ("shared", "device")

# Operations that change stored data (refused in read-only mode)
WRITES = {
    "PutObject", "UploadPart", "UploadPartCopy", "CopyObject", "CreateMultipartUpload", "CompleteMultipartUpload",
    "AbortMultipartUpload", "DeleteObject", "DeleteObjects", "CreateBucket", "DeleteBucket", "PutBucketOther",
    "PutObjectOther", "PostObject",
}
# Operations that add bytes, checked against allocations and the pool
GROWS = {"PutObject", "UploadPart", "UploadPartCopy", "CopyObject"}


def classify(method, bucket, key, query, headers):
    q = set(query)
    copy = "x-amz-copy-source" in headers
    if not bucket:
        return "ListBuckets" if method == "GET" else "Unknown"
    if not key:
        if method == "GET":
            if "list-type" in q:
                return "ListObjectsV2"
            if "uploads" in q:
                return "ListMultipartUploads"
            if "location" in q:
                return "GetBucketLocation"
            if q & {"versioning", "acl", "policy", "cors", "lifecycle", "tagging", "encryption"}:
                return "GetBucketOther"
            return "ListObjects"
        if method == "HEAD":
            return "HeadBucket"
        if method == "PUT":
            return "PutBucketOther" if q else "CreateBucket"
        if method == "DELETE":
            return "DeleteBucket"
        if method == "POST":
            return "DeleteObjects" if "delete" in q else "PostObject"
        return "Unknown"
    if method == "GET":
        if "uploadId" in q:
            return "ListParts"
        if q & {"acl", "tagging", "attributes", "retention", "legal-hold"}:
            return "GetObjectOther"
        return "GetObject"
    if method == "HEAD":
        return "HeadObject"
    if method == "PUT":
        if "partNumber" in q and "uploadId" in q:
            return "UploadPartCopy" if copy else "UploadPart"
        if q & {"acl", "tagging", "retention", "legal-hold"}:
            return "PutObjectOther"
        return "CopyObject" if copy else "PutObject"
    if method == "POST":
        if "uploads" in q:
            return "CreateMultipartUpload"
        if "uploadId" in q:
            return "CompleteMultipartUpload"
        return "PostObject"
    if method == "DELETE":
        return "AbortMultipartUpload" if "uploadId" in q else "DeleteObject"
    return "Unknown"


def error_xml(code, message, resource="", request_id=""):
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n<Error>'
        f"<Code>{escape(code)}</Code><Message>{escape(message)}</Message>"
        f"<Resource>{escape(resource)}</Resource><RequestId>{escape(request_id)}</RequestId></Error>"
    ).encode("utf-8")


def _local(tag):
    return tag.rsplit("}", 1)[-1]


def parse_delete_request(body):
    """DeleteObjects request body -> list of keys."""
    root = ET.fromstring(body)
    keys = []
    for obj in root:
        if _local(obj.tag) != "Object":
            continue
        for child in obj:
            if _local(child.tag) == "Key":
                keys.append(child.text or "")
    return keys


def parse_delete_result(body):
    """rclone's DeleteObjects answer -> (deleted keys, failed keys)."""
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return [], []
    deleted, failed = [], []
    for el in root:
        key = next((c.text or "" for c in el if _local(c.tag) == "Key"), None)
        if key is None:
            continue
        (deleted if _local(el.tag) == "Deleted" else failed).append(key)
    return deleted, failed
