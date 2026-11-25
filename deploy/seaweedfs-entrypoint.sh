#!/bin/sh
# Writes the S3 credentials file from environment variables, then starts SeaweedFS
# as a single-node server with the S3 API on port 8333.
set -eu
mkdir -p /etc/seaweedfs
cat > /etc/seaweedfs/s3.json <<JSON
{
  "identities": [
    {
      "name": "leenax",
      "credentials": [{ "accessKey": "${S3_ACCESS_KEY}", "secretKey": "${S3_SECRET_KEY}" }],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    }
  ]
}
JSON
exec weed server -dir=/data -volume.max=0 -s3 -s3.port=8333 -s3.config=/etc/seaweedfs/s3.json
