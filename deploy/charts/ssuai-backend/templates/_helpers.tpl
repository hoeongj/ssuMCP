{{- define "ssuai-backend.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "ssuai-backend.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "ssuai-backend.name" . -}}
{{- end -}}
{{- end -}}

{{- define "ssuai-backend.labels" -}}
app.kubernetes.io/name: {{ include "ssuai-backend.fullname" . }}
app.kubernetes.io/part-of: ssuai
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "ssuai-backend.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ssuai-backend.fullname" . }}
{{- end -}}
