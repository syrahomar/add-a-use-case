package team.projectpulse.student;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import team.projectpulse.course.Course;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.section.Section;
import team.projectpulse.system.UserUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * The course-admin check behind deactivating a student (UC-STU-deactivate-student, BR-student-lifecycle). It must
 * fail closed: an unknown student or an unauthenticated caller is denied, never an exception.
 */
@ExtendWith(MockitoExtension.class)
class StudentSecurityServiceTest {

    @Mock
    StudentRepository studentRepository;

    @Mock
    UserUtils userUtils;

    @InjectMocks
    StudentSecurityService studentSecurityService;

    Student john;


    @BeforeEach
    void setUp() {
        Instructor admin = new Instructor("b.wei@abc.edu", "Bingyang", "Wei", "b.wei@abc.edu", "123456", true, "admin instructor");
        admin.setId(1);
        Course course = new Course("COSC 40993 Senior Design", "Senior design");
        course.setCourseAdmin(admin);
        Section section = new Section();
        section.setCourse(course);
        this.john = new Student("j.smith@abc.edu", "John", "Smith", "j.smith@abc.edu", "123456", true, "student");
        this.john.setId(4);
        section.addStudent(this.john);
    }

    @Test
    void courseAdminOfTheStudentsCourseIsAdmitted() {
        given(this.userUtils.getUserId()).willReturn(1);
        given(this.studentRepository.findById(4)).willReturn(Optional.of(this.john));

        assertThat(this.studentSecurityService.isCurrentUserCourseAdminOfStudentCourse(4)).isTrue();
    }

    @Test
    void anotherInstructorIsDenied() {
        given(this.userUtils.getUserId()).willReturn(3);
        given(this.studentRepository.findById(4)).willReturn(Optional.of(this.john));

        assertThat(this.studentSecurityService.isCurrentUserCourseAdminOfStudentCourse(4)).isFalse();
    }

    @Test
    void theStudentHerselfIsDenied() {
        given(this.userUtils.getUserId()).willReturn(4);
        given(this.studentRepository.findById(4)).willReturn(Optional.of(this.john));

        assertThat(this.studentSecurityService.isCurrentUserCourseAdminOfStudentCourse(4)).isFalse();
    }

    @Test
    void unknownStudentIsDeniedNotThrown() {
        given(this.userUtils.getUserId()).willReturn(1);
        given(this.studentRepository.findById(999999)).willReturn(Optional.empty());

        assertThat(this.studentSecurityService.isCurrentUserCourseAdminOfStudentCourse(999999)).isFalse();
    }

    @Test
    void callerWithoutAUserIdIsDenied() {
        given(this.userUtils.getUserId()).willReturn(null);

        assertThat(this.studentSecurityService.isCurrentUserCourseAdminOfStudentCourse(4)).isFalse();
    }

}
