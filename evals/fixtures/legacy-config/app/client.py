from app import settings


def build_request(path):
    return {"url": "https://api.example.com" + path, "timeout": settings.REQUEST_TIMEOUT_SECONDS}
