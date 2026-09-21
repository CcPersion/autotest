FROM python:3.12-alpine
COPY f2-01-target.py /opt/f2-01-target.py
ENTRYPOINT ["python", "/opt/f2-01-target.py"]
