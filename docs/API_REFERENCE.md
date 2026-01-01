# Paperless-ngx API Reference

## Übersicht

Diese Dokumentation beschreibt die Paperless-ngx REST API Endpoints, die von der App verwendet werden.

**Offizielle Dokumentation:** https://docs.paperless-ngx.com/api/

**Browsable API:** `https://<your-server>/api/schema/view/`

---

## 1. Authentifizierung

### 1.1 Token abrufen

Authentifiziert einen Benutzer und gibt ein API-Token zurück.

**Endpoint:** `POST /api/token/`

**Request:**
```http
POST /api/token/ HTTP/1.1
Content-Type: application/x-www-form-urlencoded

username=admin&password=secret123
```

**Response (200 OK):**
```json
{
    "token": "abc123def456ghi789jkl012mno345pqr678stu"
}
```

**Response (400 Bad Request):**
```json
{
    "non_field_errors": ["Unable to log in with provided credentials."]
}
```

**Verwendung in Retrofit:**
```kotlin
@POST("api/token/")
@FormUrlEncoded
suspend fun getToken(
    @Field("username") username: String,
    @Field("password") password: String
): TokenResponse
```

### 1.2 Token verwenden

Alle authentifizierten Requests benötigen den Header:

```http
Authorization: Token <token>
```

---

## 2. Tags

### 2.1 Tags abrufen

Gibt alle Tags zurück.

**Endpoint:** `GET /api/tags/`

**Request:**
```http
GET /api/tags/ HTTP/1.1
Authorization: Token <token>
```

**Response (200 OK):**
```json
{
    "count": 5,
    "next": null,
    "previous": null,
    "results": [
        {
            "id": 1,
            "name": "Rechnung",
            "color": "#ff0000",
            "match": "",
            "matching_algorithm": 0,
            "is_inbox_tag": false
        },
        {
            "id": 2,
            "name": "Vertrag",
            "color": "#00ff00",
            "match": "",
            "matching_algorithm": 0,
            "is_inbox_tag": false
        }
    ]
}
```

**Paginierung:**
- `?page=2` - Nächste Seite
- `?page_size=100` - Ergebnisse pro Seite (max 100)

**Verwendung in Retrofit:**
```kotlin
@GET("api/tags/")
suspend fun getTags(): TagsResponse
```

### 2.2 Tag erstellen

Erstellt einen neuen Tag.

**Endpoint:** `POST /api/tags/`

**Request:**
```http
POST /api/tags/ HTTP/1.1
Authorization: Token <token>
Content-Type: application/json

{
    "name": "Neuer Tag",
    "color": "#0000ff",
    "match": "",
    "matching_algorithm": 0
}
```

**Response (201 Created):**
```json
{
    "id": 6,
    "name": "Neuer Tag",
    "color": "#0000ff",
    "match": "",
    "matching_algorithm": 0,
    "is_inbox_tag": false
}
```

**Matching Algorithms:**

| Wert | Algorithmus |
|------|-------------|
| 0 | None |
| 1 | Any |
| 2 | All |
| 3 | Literal |
| 4 | Regex |
| 5 | Fuzzy |

---

## 3. Dokumenttypen

### 3.1 Dokumenttypen abrufen

**Endpoint:** `GET /api/document_types/`

**Response (200 OK):**
```json
{
    "count": 3,
    "next": null,
    "previous": null,
    "results": [
        {
            "id": 1,
            "name": "Rechnung",
            "match": "",
            "matching_algorithm": 0
        },
        {
            "id": 2,
            "name": "Kontoauszug",
            "match": "",
            "matching_algorithm": 0
        }
    ]
}
```

---

## 4. Korrespondenten

### 4.1 Korrespondenten abrufen

**Endpoint:** `GET /api/correspondents/`

**Response (200 OK):**
```json
{
    "count": 2,
    "next": null,
    "previous": null,
    "results": [
        {
            "id": 1,
            "name": "Telekom",
            "match": "",
            "matching_algorithm": 0
        }
    ]
}
```

---

## 5. Dokumente

### 5.1 Dokument hochladen

**Endpoint:** `POST /api/documents/post_document/`

**Request (Multipart Form):**
```http
POST /api/documents/post_document/ HTTP/1.1
Authorization: Token <token>
Content-Type: multipart/form-data; boundary=----Boundary

------Boundary
Content-Disposition: form-data; name="document"; filename="scan.pdf"
Content-Type: application/pdf

<binary data>
------Boundary
Content-Disposition: form-data; name="title"

Rechnung Dezember 2024
------Boundary
Content-Disposition: form-data; name="tags"

1
------Boundary
Content-Disposition: form-data; name="tags"

2
------Boundary--
```

**Optionale Felder:**

| Feld | Typ | Beschreibung |
|------|-----|--------------|
| `document` | File | Die Dokumentdatei (required) |
| `title` | String | Titel des Dokuments |
| `created` | DateTime | Erstelldatum (ISO 8601) |
| `correspondent` | Integer | Korrespondent-ID |
| `document_type` | Integer | Dokumenttyp-ID |
| `tags` | Integer[] | Tag-IDs (mehrfach möglich) |
| `archive_serial_number` | Integer | Archivnummer |

**Response (200 OK):**
```
"a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

**Wichtig:** Die Response ist ein **einfacher String** (Task-UUID), kein JSON-Objekt!

**Verwendung in Retrofit:**
```kotlin
@Multipart
@POST("api/documents/post_document/")
suspend fun uploadDocument(
    @Part document: MultipartBody.Part,
    @Part("title") title: RequestBody? = null,
    @Part("tags") tags: RequestBody? = null
): ResponseBody  // NICHT: UploadResponse!

// Im Repository:
val taskId = response.string().trim().removeSurrounding("\"")
```

### 5.2 Dokumente abrufen

**Endpoint:** `GET /api/documents/`

**Query-Parameter:**

| Parameter | Beschreibung |
|-----------|--------------|
| `query` | Volltextsuche |
| `tags__id__in` | Filter nach Tag-IDs |
| `correspondent__id` | Filter nach Korrespondent |
| `document_type__id` | Filter nach Dokumenttyp |
| `created__date__gte` | Erstellt nach Datum |
| `created__date__lte` | Erstellt vor Datum |
| `ordering` | Sortierung (z.B. `-created`) |

**Beispiel:**
```http
GET /api/documents/?query=rechnung&tags__id__in=1,2&ordering=-created
```

### 5.3 Einzelnes Dokument

**Endpoint:** `GET /api/documents/{id}/`

**Response:**
```json
{
    "id": 123,
    "title": "Rechnung Dezember",
    "content": "Volltext des Dokuments...",
    "tags": [1, 2],
    "correspondent": 1,
    "document_type": 1,
    "created": "2024-12-15T10:30:00Z",
    "added": "2024-12-15T10:35:00Z",
    "archive_serial_number": null,
    "original_file_name": "scan.pdf"
}
```

---

## 6. Task-Status

### 6.1 Upload-Status prüfen

Nach dem Upload kann der Task-Status abgefragt werden.

**Endpoint:** `GET /api/tasks/?task_id={uuid}`

**Response:**
```json
{
    "id": "a1b2c3d4-...",
    "status": "SUCCESS",
    "result": 123,  // Document ID
    "date_created": "2024-12-15T10:35:00Z",
    "date_done": "2024-12-15T10:35:05Z"
}
```

**Status-Werte:**

| Status | Bedeutung |
|--------|-----------|
| PENDING | Wartend |
| STARTED | In Bearbeitung |
| SUCCESS | Erfolgreich |
| FAILURE | Fehlgeschlagen |

---

## 7. Fehlerbehandlung

### 7.1 HTTP Status Codes

| Code | Bedeutung |
|------|-----------|
| 200 | OK |
| 201 | Created |
| 400 | Bad Request |
| 401 | Unauthorized |
| 403 | Forbidden |
| 404 | Not Found |
| 500 | Server Error |

### 7.2 Fehler-Response

```json
{
    "detail": "Authentication credentials were not provided."
}
```

Oder bei Validierungsfehlern:

```json
{
    "name": ["This field is required."],
    "tags": ["Invalid pk \"999\" - object does not exist."]
}
```

---

## 8. Rate Limiting

Paperless-ngx hat standardmäßig kein Rate Limiting. Bei Bedarf kann dies serverseitig konfiguriert werden.

**Empfehlung:** Uploads nicht parallel ausführen, um Server nicht zu überlasten.

---

## 9. Versionierung

Die API unterstützt Versionierung über den Accept-Header:

```http
Accept: application/json; version=9
```

Ohne Version wird API v1 verwendet (Kompatibilitätsmodus).

**Aktuelle Version:** 9 (Stand: Paperless-ngx 2.x)

---

## 10. Bekannte Eigenheiten

| Endpoint | Eigenheit | Workaround |
|----------|-----------|------------|
| `post_document` | Response ist String, kein JSON | `ResponseBody.string()` |
| `tags` | Mehrere Tags = mehrere Form-Fields | Einzelne IDs senden |
| Pagination | Max 100 Ergebnisse pro Seite | Alle Seiten abrufen |

---

## Referenzen

- [Offizielle API-Dokumentation](https://docs.paperless-ngx.com/api/)
- [GitHub Repository](https://github.com/paperless-ngx/paperless-ngx)
- [API Discussions](https://github.com/paperless-ngx/paperless-ngx/discussions/categories/api)
