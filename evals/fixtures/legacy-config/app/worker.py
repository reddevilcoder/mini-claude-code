from app.settings import REQUEST_TIMEOUT_SECONDS


def job_deadline(start):
    return start + REQUEST_TIMEOUT_SECONDS * 3
