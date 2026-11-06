# Students (STU) Design

> Realizes: UC-STU-deactivate-student
> Depends on: FR-SEC-active-account, FR-SEC-deny-unauthorized, BR-student-lifecycle, BR-section-scoped-access, BR-role-based-access, BR-submission-owed
> See: [use cases](../requirements/use-cases.md#student), [SRS security requirements](../requirements/software-requirements-specification.md#security-and-authorization-requirements), [architectural-design.md](architectural-design.md#shared-foundation-components) (shared foundation component view, Crosscutting Concepts: authentication)
> Designed against `main` at `59f0267` (2026-11-06).

## Overview

This area covers the student account inside the foundation's `student` slice of the REST API container. The STU use cases built before this workflow (view, delete, invite, …) are recorded in [`../traceability.md`](../traceability.md) and have no design doc of their own yet; this doc starts with deactivation and grows as the other STU use cases are designed.

Before this design, deactivation existed only as the `enabled` toggle in the generic edit form (`PUT /students/{studentId}`, `StudentService.updateStudent`). It had no confirmation and no warning. Its route rule (`UserOwnershipAuthorizationManager`, which admits an instructor of the student's course section or the student herself) plus the service's `ROLE_instructor` check meant only an instructor of the student's course section could flip it, so a course admin who is not also one of those instructors could not, although BR-student-lifecycle allows her to. A student who was already logged in kept a working token for up to two hours (FR-SEC-active-account). Deactivation now gets its own endpoint and confirmation dialog. The security filter chain rejects a deactivated account on every request, and peer evaluations stop offering a deactivated teammate.

## Components & classes

```mermaid
classDiagram
    direction LR
    class StudentController {
        <<reused>>
        +deactivateStudent(studentId) Result
    }
    class StudentService {
        <<reused>>
        +deactivateStudent(studentId) Student
    }
    class StudentSecurityService {
        <<reused>>
        +isCurrentUserInstructorOrAdminOfStudentSection(studentId) boolean
    }
    class StudentInstructorAuthorizationManager {
        <<reused>>
    }
    class SecurityConfiguration {
        <<reused>>
        +jwtAuthenticationConverter() active-account check
    }
    class UserRepository {
        <<reused>>
    }
    class EvaluationService {
        <<reused>>
        +savePeerEvaluation() rejects inactive evaluatee
    }
    class Student {
        <<reused, entity>>
        enabled
        team
        section
    }
    StudentController --> StudentService
    StudentService --> Student
    StudentInstructorAuthorizationManager --> StudentSecurityService
    SecurityConfiguration --> StudentInstructorAuthorizationManager : route rule
    SecurityConfiguration --> UserRepository : per-request lookup
    EvaluationService --> Student : evaluatee.enabled
```

Every class already exists; deactivation adds methods, a route rule, and a check, not classes.

- Backend: [`StudentController`](../../backend/src/main/java/team/projectpulse/student/StudentController.java), [`StudentService`](../../backend/src/main/java/team/projectpulse/student/StudentService.java), [`StudentSecurityService`](../../backend/src/main/java/team/projectpulse/student/StudentSecurityService.java), [`StudentInstructorAuthorizationManager`](../../backend/src/main/java/team/projectpulse/security/authorizationmanagers/StudentInstructorAuthorizationManager.java), [`SecurityConfiguration`](../../backend/src/main/java/team/projectpulse/security/SecurityConfiguration.java), [`UserRepository`](../../backend/src/main/java/team/projectpulse/user/UserRepository.java), [`EvaluationService`](../../backend/src/main/java/team/projectpulse/evaluation/EvaluationService.java).
- Frontend: [`Students.vue`](../../frontend/src/pages/students/Students.vue) (Deactivate control + confirmation), [`apis/student`](../../frontend/src/apis/student/index.ts) (`deactivateStudent`), [`SubmitTeamsEvaluations.vue`](../../frontend/src/pages/evaluations/SubmitTeamsEvaluations.vue) (lists only active teammates).

## Sequence

### UC-STU-deactivate-student: main success scenario

```mermaid
sequenceDiagram
    actor I as Course admin / instructor
    participant V as Students.vue
    participant API as apis/student
    participant SEC as Security filter chain
    participant C as StudentController
    participant S as StudentService
    participant R as StudentRepository
    I->>V: 1-2. find and view the student (UC-STU-view-student)
    I->>V: 3. Deactivate (offered only while enabled, ext. 3a)
    V->>I: 4. confirmation dialog: consequences + warning
    alt 5a. Cancel
        V->>I: student unchanged, no request sent
    else 5. Confirm
        V->>API: deactivateStudent(studentId)
        API->>SEC: PATCH /students/{studentId}/deactivate
        SEC->>SEC: caller's account active? section instructor or course admin? (ext. 3b, 6a)
        SEC->>C: allowed
        C->>S: deactivateStudent(studentId)
        S->>R: findById; already deactivated → reject (ext. 3a)
        S->>R: save with enabled = false, team kept
        C-->>V: Result success, StudentDto
        V->>I: 6. "student deactivated" alert; status shows Disabled
    end
```

### FR-SEC-active-account: a deactivated student who is still logged in

```mermaid
sequenceDiagram
    actor St as Deactivated student
    participant F as JWT authentication converter
    participant U as UserRepository
    St->>F: any protected request with her unexpired JWT
    F->>U: findById(userId claim)
    U-->>F: enabled = false
    F-->>St: 401; the SPA clears the token and redirects to login
    St->>F: log in again (HTTP Basic)
    F-->>St: 401 (MyUserPrincipal.isEnabled is false; existing behavior)
```

## API contract

| Endpoint or job | Caller (who may) | Request | Success | Errors, by extension |
|---|---|---|---|---|
| `PATCH /api/v1/students/{studentId}/deactivate` | an instructor of the student's course section, or the course admin of that course section's course (BR-student-lifecycle) | path `studentId`; no body | HTTP 200, `Result` success, message "Deactivate student successfully", data: the updated `StudentDto` with `enabled` false | 3a already deactivated: HTTP 409, `Result` failure with `StatusCode.CONFLICT`, message "The student is already deactivated.", student unchanged. 3b caller not authorized: HTTP 403. 6a unknown `studentId`: HTTP 403, the same answer as 3b, because the authorization manager fails closed before the controller runs |
| every authenticated request (change) | — | a bearer JWT whose `userId` claim names a deactivated student or instructor, or no account at all | — | HTTP 401 (FR-SEC-active-account) |
| `POST /api/v1/evaluations` and edits of a submitted peer evaluation (change) | student | an evaluatee whose account is deactivated | — | HTTP 400 via `PeerEvaluationIllegalArgumentException`, message "You cannot evaluate a deactivated student.", nothing saved |

## Key decisions

- **A dedicated `PATCH /students/{studentId}/deactivate`, not the edit form's `PUT`.** `PUT` rewrites every field under one rule, so it can't carry this use case's authorization, its extension 3a, or a test tagged to it. *Rejected:* keep using `PUT` with `enabled` false. `PUT`'s `enabled` toggle stays in place for now because UC-STU-reactivate-student still depends on it; it should be removed when reactivation gets its own endpoint (open question 1).
- **Already deactivated is a conflict (409), not a no-op.** A new exception mapped in `ExceptionHandlerAdvice` to HTTP 409 and `StatusCode.CONFLICT`; the SPA's shared error handling shows its message as a toast (extension 3a2). *Rejected:* returning success unchanged, which would hide a stale page from the instructor.
- **Unknown student id answers 403, the same as unauthorized (extension 6a).** Every authorization manager here fails closed before the controller, so a caller cannot probe which student ids exist (FR-SEC-deny-unauthorized). The use case's 6a is worded to match. *Rejected:* a 404 "student not found", which would tell any instructor which ids exist in other course sections.
- **Immediate cutoff through the JWT authentication converter.** After the token's signature and expiry are checked, the converter loads the account named by the `userId` claim from `UserRepository` and, if it is deactivated or no longer exists, throws `DisabledException`. Being an `AuthenticationException`, it goes to the bearer-token entry point and becomes a 401, which the SPA already handles by clearing the token and redirecting to login. The check covers every `PeerEvaluationUser`, so it also covers deactivated instructors (the instructor half of FR-SEC-active-account) at no extra cost. A request to a `permitAll` route that carries a stale token also gets 401; the SPA then logs in afresh. Authentication stays stateless (no session, no shared token state). *Rejected:* a token denylist, which adds shared state that every instance must see and so cuts against KD-self-issued-jwt's stateless model; and a shorter token lifetime, which still leaves a window and logs everyone out more often. *Cost:* one primary-key read per request.
- **Who may: an instructor of the student's course section, or the course admin of its course.** This is a new `StudentSecurityService` check beside `isCurrentUserInstructorOfStudentSection`, failing closed like every check there. It passes when the caller's id is among `student.section.instructors`, which is a set, so an instructor assigned to several course sections is handled; or when the caller is `student.section.course.courseAdmin`. It compares ids from the JWT's `userId` claim, never the caller's default course section. *Rejected:* `hasAuthority("ROLE_instructor")`, which would let an instructor of any course section deactivate the student, contradicting BR-section-scoped-access; and `UserOwnershipAuthorizationManager`, which would let a student deactivate herself.
- **The student stays on her team.** Deactivation changes only `enabled`. *Rejected:* removing her from the team, which would make reactivation lossy and detach her from her team's work.
- **Peer evaluations: hide her in the form and guard on the server.** `SubmitTeamsEvaluations.vue` lists only teammates whose `StudentDto.enabled` is true, and `EvaluationService` rejects a deactivated evaluatee, per BR-submission-owed, both for a new peer evaluation and for an edit of one already submitted. Evaluations already stored are kept and still count in reports. *Rejected:* filtering `GET /students/teams/{teamId}`, because instructor team views read the same endpoint and must still show her.
- **The control is a Deactivate button in the Operations column of `Students.vue`**, beside Edit, shown only while the row's `enabled` is true (extension 3a1) and shown to every instructor; the server enforces 3b. *Rejected:* hiding it per caller, which would duplicate the authorization rule in the browser.
- **The confirmation is a frontend dialog** (`ElMessageBox.confirm`, warning type). It reads: "Deactivate <first name> <last name>? They will lose access immediately and can no longer log in or submit weekly activity reports and peer evaluations. Their account, team, and submitted work are kept, and you can reactivate them later. This is not a deletion." Buttons: Deactivate / Cancel. On success the page shows "<first name> <last name> has been deactivated." and reloads the row. *Rejected:* a server-side two-step confirmation token, which adds no protection beyond requiring a separate, explicit request.

## Data model

No change. `enabled` already exists on `PeerEvaluationUser`, the superclass of `Student`; see the SRS's Business Domain Model.

## Reuse & cross-cutting

- **Authentication & RBAC** (`security`): a new route rule, a new ownership check in the existing `StudentInstructorAuthorizationManager` style, and the active-account check in the JWT converter.
- **Login**: `MyUserPrincipal.isEnabled` already blocks a deactivated account from logging in.
- **Submission status** (`notification/SubmissionStatusService`): already counts only active students (BR-submission-owed); unchanged.
- **Client error handling** (`frontend/src/utils/request.ts`): a 401 already clears the session and redirects to login, which completes the cutoff on the SPA side.

## Tests

| Use case, flow | Level (unit / integration) | Asserts |
|---|---|---|
| UC-STU-deactivate-student main: an instructor of the student's course section deactivates an active student | integration | 200; `enabled` false in the database; team unchanged; her weekly activity reports and peer evaluations still present |
| main: the course admin, not an instructor of the course section, deactivates | integration | 200; `enabled` false |
| main: the service changes only `enabled` | unit (`StudentServiceTest`) | `enabled` false; team and other fields unchanged; saved once |
| 3a: the student is already deactivated | unit and integration | HTTP 409, `StatusCode.CONFLICT`, message "The student is already deactivated."; student unchanged |
| main: an instructor assigned to two course sections deactivates a student in her second one | integration | 200 |
| 3b: the course admin of a different course | integration | 403; student unchanged |
| 3b: an instructor of a different course section | integration | 403; student unchanged |
| 3b: a student calls the endpoint, including on herself | integration | 403; student unchanged |
| 5a: the course admin or instructor cancels | manual (frontend) | no request is sent; student unchanged |
| 6a: unknown `studentId` | integration | 403, not 500 |
| FR-SEC-active-account: a deactivated student's unexpired JWT | integration | her next protected request returns 401 |
| FR-SEC-active-account: a deactivated student logs in | integration | 401 |
| FR-SEC-active-account: a JWT whose `userId` names no account | integration | 401, not 500 |
| BR-submission-owed: a teammate submits, or edits, a peer evaluation of a deactivated student | unit (`EvaluationService`) | `PeerEvaluationIllegalArgumentException`; nothing saved |
| BR-submission-owed: the peer evaluation form | manual (frontend) | the deactivated teammate is not listed |

## Open questions / risks

1. `PUT /students/{studentId}` can still toggle `enabled` (an instructor of the student's course section, no confirmation) until UC-STU-reactivate-student is designed and the toggle can be removed. It is no broader than the new rule, so it breaks no business rule meanwhile.
2. The design checks the course admin explicitly rather than relying on her being among the course section's instructors, so it holds whether or not she is.
3. The active-account check adds one database read to every authenticated request. That is negligible at class scale, but it is the first per-request database read in authentication.
