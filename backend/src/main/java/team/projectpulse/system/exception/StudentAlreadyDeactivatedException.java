package team.projectpulse.system.exception;

/**
 * A deactivation request for a student who is already deactivated (UC-STU-deactivate-student, extension 3a2).
 * Mapped to HTTP 409 and {@code StatusCode.CONFLICT}; the student is left unchanged.
 */
public class StudentAlreadyDeactivatedException extends RuntimeException {

    public StudentAlreadyDeactivatedException() {
        super("The student is already deactivated.");
    }

}
