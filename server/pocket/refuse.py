"""The refusal every gateway route raises: an HTTP status, a stable error code and a message for people."""


class Refuse(Exception):
    def __init__(self, status, code, message, headers=None, body=None):
        super().__init__(message)
        self.status, self.code, self.message, self.headers = status, code, message, headers or {}
        self.body = body or {}          # extra fields for a JSON error body (e.g. the resync cursor)
