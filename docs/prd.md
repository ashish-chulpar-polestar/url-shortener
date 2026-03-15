h1. MTI Scores API - Product Requirements Document

h2. 1. Overview

API to retrieve Maritime Transportation Indicator (MTI) scores for vessels by IMO number, with optional filtering by year and month.

h2. 2. API Specification

h3. Endpoint

{noformat}GET /api/v1/vessels/{imo}/mti-scores{noformat}

h3. Path Parameters

||Parameter||Type||Required||Description||
|imo|string|Yes|7-digit IMO number|

h3. Query Parameters

||Parameter||Type||Required||Description||
|year|integer|No|Filter by year (e.g., 2024)|
|month|integer|No|Filter by month (1-12)|

h3. Business Logic

* If no year/month specified: Return *latest* available scores for the IMO
* If year specified: Return latest scores for that year
* If year + month specified: Return scores for that specific month
* If month specified without year: Return error ERR_102

h2. 3. Response Structure

h3. Success Response

{code:json}{
  "meta": {
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "request_timestamp": "2024-01-15T10:30:00Z"
  },
  "data": {
    "imo_number": "1234567",
    "year": 2024,
    "month": 1,
    "scores": {
      "mti_score": 85.50,
      "vessel_score": 90.00,
      "reporting_score": 88.75,
      "voyages_score": 82.30,
      "emissions_score": 87.60,
      "sanctions_score": 100.00
    },
    "metadata": {
      "created_at": "2024-01-01T00:00:00Z",
      "updated_at": "2024-01-01T00:00:00Z"
    }
  }
}{code}

h3. Error Response

{code:json}{
  "meta": {
    "request_id": "550e8400-e29b-41d4-a716-446655440000",
    "request_timestamp": "2024-01-15T10:30:00Z"
  },
  "data": {
    "error_code": "ERR_101",
    "title": "Resource Not Found",
    "message": "No MTI scores found for IMO 1234567"
  }
}{code}

h2. 4. Error Codes

||Code||Title||HTTP Status||Description||
|ERR_101|Resource Not Found|404|No scores found for given IMO/year/month|
|ERR_102|Invalid Parameters|400|Month specified without year|
|ERR_103|Invalid IMO Format|400|IMO number must be 7 digits|
|ERR_104|Invalid Date Range|400|Invalid year or month value|
|ERR_105|Internal Server Error|500|Database connection or query error|

h2. 5. Acceptance Criteria & Test Cases

h3. AC1: Retrieve Latest Scores

*Request:* {{GET /api/v1/vessels/9123456/mti-scores}}
*Expected:* Return most recent scores for IMO 9123456
*Status:* 200 OK

h3. AC2: Retrieve Scores for Specific Year

*Request:* {{GET /api/v1/vessels/9123456/mti-scores?year=2023}}
*Expected:* Return latest scores from 2023
*Status:* 200 OK

h3. AC3: Retrieve Scores for Specific Month

*Request:* {{GET /api/v1/vessels/9123456/mti-scores?year=2023&month=6}}
*Expected:* Return scores for June 2023
*Status:* 200 OK

h3. AC4: IMO Not Found

*Request:* {{GET /api/v1/vessels/9999999/mti-scores}}
*Expected:* Error ERR_101
*Status:* 404

h3. AC5: Invalid IMO Format

*Request:* {{GET /api/v1/vessels/123/mti-scores}}
*Expected:* Error ERR_103
*Status:* 400

h3. AC6: Month Without Year

*Request:* {{GET /api/v1/vessels/9123456/mti-scores?month=6}}
*Expected:* Error ERR_102
*Status:* 400

h3. AC7: Invalid Month Value

*Request:* {{GET /api/v1/vessels/9123456/mti-scores?year=2023&month=13}}
*Expected:* Error ERR_104
*Status:* 400

h3. AC8: Partial Score Data

*Request:* Valid request but some score fields are NULL in DB
*Expected:* Return scores with NULL values as {{null}} in JSON
*Status:* 200 OK

h2. 6. OpenAPI Specification

{code:yaml}openapi: 3.0.3
info:
  title: MTI Scores API
  version: 1.0.0
  description: API for retrieving Maritime Transportation Indicator scores by vessel IMO

servers:
  - url: https://api.example.com/api/v1

paths:
  /vessels/{imo}/mti-scores:
    get:
      summary: Get MTI scores for a vessel
      description: Retrieves MTI scores for a vessel by IMO number, optionally filtered by year and month
      parameters:
        - name: imo
          in: path
          required: true
          schema:
            type: string
            pattern: '^[0-9]{7}$'
          description: 7-digit IMO number
        - name: year
          in: query
          required: false
          schema:
            type: integer
            minimum: 2000
            maximum: 2100
          description: Filter by year
        - name: month
          in: query
          required: false
          schema:
            type: integer
            minimum: 1
            maximum: 12
          description: Filter by month (requires year)
      responses:
        '200':
          description: Success
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/SuccessResponse'
              examples:
                latestScores:
                  summary: Latest scores
                  value:
                    meta:
                      request_id: "550e8400-e29b-41d4-a716-446655440000"
                      request_timestamp: "2024-01-15T10:30:00Z"
                    data:
                      imo_number: "9123456"
                      year: 2024
                      month: 1
                      scores:
                        mti_score: 85.50
                        vessel_score: 90.00
                        reporting_score: 88.75
                        voyages_score: 82.30
                        emissions_score: 87.60
                        sanctions_score: 100.00
                      metadata:
                        created_at: "2024-01-01T00:00:00Z"
                        updated_at: "2024-01-01T00:00:00Z"
        '400':
          description: Bad Request
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
              examples:
                invalidParams:
                  summary: Month without year
                  value:
                    meta:
                      request_id: "550e8400-e29b-41d4-a716-446655440000"
                      request_timestamp: "2024-01-15T10:30:00Z"
                    data:
                      error_code: "ERR_102"
                      title: "Invalid Parameters"
                      message: "Month parameter requires year parameter to be specified"
        '404':
          description: Not Found
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'
              examples:
                notFound:
                  summary: IMO not found
                  value:
                    meta:
                      request_id: "550e8400-e29b-41d4-a716-446655440000"
                      request_timestamp: "2024-01-15T10:30:00Z"
                    data:
                      error_code: "ERR_101"
                      title: "Resource Not Found"
                      message: "No MTI scores found for IMO 9123456"
        '500':
          description: Internal Server Error
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/ErrorResponse'

components:
  schemas:
    SuccessResponse:
      type: object
      properties:
        meta:
          $ref: '#/components/schemas/Meta'
        data:
          type: object
          properties:
            imo_number:
              type: string
            year:
              type: integer
            month:
              type: integer
            scores:
              $ref: '#/components/schemas/Scores'
            metadata:
              type: object
              properties:
                created_at:
                  type: string
                  format: date-time
                updated_at:
                  type: string
                  format: date-time

    ErrorResponse:
      type: object
      properties:
        meta:
          $ref: '#/components/schemas/Meta'
        data:
          type: object
          properties:
            error_code:
              type: string
            title:
              type: string
            message:
              type: string

    Meta:
      type: object
      properties:
        request_id:
          type: string
          format: uuid
        request_timestamp:
          type: string
          format: date-time

    Scores:
      type: object
      properties:
        mti_score:
          type: number
          format: float
          nullable: true
        vessel_score:
          type: number
          format: float
          nullable: true
        reporting_score:
          type: number
          format: float
          nullable: true
        voyages_score:
          type: number
          format: float
          nullable: true
        emissions_score:
          type: number
          format: float
          nullable: true
        sanctions_score:
          type: number
          nullable: true{code}

h2. 7. Database Query Logic

h3. Latest Scores (No filters)

{code:sql}SELECT * FROM mti_scores_history
WHERE imo_number = ?
ORDER BY year DESC, month DESC
LIMIT 1;{code}

h3. Filter by Year

{code:sql}SELECT * FROM mti_scores_history
WHERE imo_number = ? AND year = ?
ORDER BY month DESC
LIMIT 1;{code}

h3. Filter by Year + Month

{code:sql}SELECT * FROM mti_scores_history
WHERE imo_number = ? AND year = ? AND month = ?
LIMIT 1;{code}

h2. 8. Implementation Notes

# *UUID Generation*: Use {{uuid.v4()}} or database UUID function for request_id
# *Timestamp*: Use ISO 8601 format (UTC)
# *IMO Validation*: Regex pattern {{^[0-9]{7}$}}
# *NULL Handling*: Return {{null}} in JSON for NULL database values
# *Logging*: Log request_id with all operations for traceability
# *Performance*: Add index on {{(imo_number, year DESC, month DESC)}} for optimal query performance

h2. 9. Security Considerations

* Rate limiting: 100 requests per minute per API key
* Input validation on all parameters
* SQL injection prevention via parameterized queries
* No sensitive data exposure in error messages

----

*Document Version:* 1.0
*Last Updated:* 2024-01-15