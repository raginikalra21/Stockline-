#!/bin/sh
set -eu

case "${DB_URL:-}" in
    postgres://*|postgresql://*)
        connection="${DB_URL#*://}"
        case "$connection" in *@*) connection="${connection#*@}" ;; esac
        DB_URL="jdbc:postgresql://${connection}"
        export DB_URL
        ;;
esac

exec java -XX:MaxRAMPercentage=75 -jar /app/stockline.jar "$@"
