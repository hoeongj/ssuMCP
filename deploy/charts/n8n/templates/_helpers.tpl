{{- define "n8n.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "n8n.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- include "n8n.name" . -}}
{{- end -}}
{{- end -}}

{{- define "n8n.labels" -}}
app.kubernetes.io/name: {{ include "n8n.fullname" . }}
app.kubernetes.io/part-of: ssuai
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end -}}

{{- define "n8n.selectorLabels" -}}
app.kubernetes.io/name: {{ include "n8n.fullname" . }}
{{- end -}}
