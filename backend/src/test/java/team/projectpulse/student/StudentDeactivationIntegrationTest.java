package team.projectpulse.student;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import team.projectpulse.AbstractIntegrationTest;
import team.projectpulse.activity.ActivityRepository;
import team.projectpulse.evaluation.PeerEvaluationRepository;
import team.projectpulse.instructor.Instructor;
import team.projectpulse.instructor.InstructorRepository;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.StatusCode;
import team.projectpulse.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-STU-deactivate-student through {@code PATCH /students/{studentId}/deactivate}: the main flow, extension 3a2
 * (already deactivated), 3b (not authorized), and 6a (no such student).
 *
 * <p>Seed data: Bingyang Wei is course admin of COSC 40993 and an instructor of both its course sections; Bill
 * Gates is an instructor of both; John Smith and Eric Hudson are students in 2023-2024 on Team1. Tim Cook is course
 * admin and the only instructor of EE 30323, whose course section holds Jana Norton.
 */
@DisplayName("Integration tests for deactivating a student")
class StudentDeactivationIntegrationTest extends AbstractIntegrationTest {

    private static final int NO_SUCH_ID = 999999;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StudentRepository studentRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    InstructorRepository instructorRepository;

    @Autowired
    SectionRepository sectionRepository;

    @Autowired
    ActivityRepository activityRepository;

    @Autowired
    PeerEvaluationRepository peerEvaluationRepository;

    @Value("${api.endpoint.base-url}")
    String baseUrl;

    Student john;

    Student eric;

    Student jana;


    @BeforeEach
    void setUp() {
        this.john = student("j.smith@abc.edu");
        this.eric = student("e.hudson@abc.edu");
        this.jana = student("j.norton@abc.edu");
    }

    private Student student(String username) {
        Integer id = this.userRepository.findByUsername(username).orElseThrow().getId();
        return this.studentRepository.findById(id).orElseThrow();
    }

    private Instructor instructor(String username) {
        Integer id = this.userRepository.findByUsername(username).orElseThrow().getId();
        return this.instructorRepository.findById(id).orElseThrow();
    }

    private String login(String email) throws Exception {
        MvcResult mvcResult = this.mockMvc.perform(post(this.baseUrl + "/users/login").with(httpBasic(email, "123456"))).andReturn();
        JSONObject json = new JSONObject(mvcResult.getResponse().getContentAsString());
        return "Bearer " + json.getJSONObject("data").getString("token");
    }

    private ResultActions deactivate(Integer studentId, String token) throws Exception {
        return this.mockMvc.perform(patch(this.baseUrl + "/students/" + studentId + "/deactivate").accept(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token));
    }

    private long activitiesOf(Student student) {
        return this.activityRepository.findAll().stream().filter(a -> a.getStudent().getId().equals(student.getId())).count();
    }

    private long evaluationsOfOrBy(Student student) {
        return this.peerEvaluationRepository.findAll().stream()
                .filter(e -> e.getEvaluatee().getId().equals(student.getId()) || e.getEvaluator().getId().equals(student.getId()))
                .count();
    }

    @Test
    @DisplayName("Main: an instructor of the student's course section deactivates her; team and submitted work are kept")
    void instructorOfTheCourseSectionDeactivatesAStudent() throws Exception {
        Integer teamIdBefore = this.john.getTeam().getTeamId();
        long activitiesBefore = activitiesOf(this.john);
        long evaluationsBefore = evaluationsOfOrBy(this.john);
        assertThat(activitiesBefore + evaluationsBefore).isPositive(); // The seed gives John submitted work to keep

        deactivate(this.john.getId(), login("b.gates@abc.edu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.message").value("Deactivate student successfully"))
                .andExpect(jsonPath("$.data.id").value(this.john.getId()))
                .andExpect(jsonPath("$.data.enabled").value(false));

        Student after = this.studentRepository.findById(this.john.getId()).orElseThrow();
        assertThat(after.isEnabled()).isFalse();
        assertThat(after.getTeam().getTeamId()).isEqualTo(teamIdBefore);
        assertThat(activitiesOf(after)).isEqualTo(activitiesBefore);
        assertThat(evaluationsOfOrBy(after)).isEqualTo(evaluationsBefore);
    }

    @Test
    @DisplayName("Main: the course admin deactivates a student even when she is not an instructor of the course section")
    void courseAdminWhoIsNotASectionInstructorDeactivatesAStudent() throws Exception {
        String weiToken = login("b.wei@abc.edu");
        Section section = this.john.getSection();
        section.removeInstructor(instructor("b.wei@abc.edu"));
        this.sectionRepository.saveAndFlush(section);

        deactivate(this.john.getId(), weiToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS))
                .andExpect(jsonPath("$.data.enabled").value(false));

        assertThat(this.studentRepository.findById(this.john.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Main: an instructor of two course sections deactivates a student outside her default course section")
    void instructorOfTwoCourseSectionsDeactivatesAStudentInTheOtherOne() throws Exception {
        String billToken = login("b.gates@abc.edu");
        Instructor bill = instructor("b.gates@abc.edu");
        Section other = bill.getSections().stream()
                .filter(s -> !s.getSectionId().equals(this.john.getSection().getSectionId()))
                .findFirst().orElseThrow();
        bill.setDefaultSection(other);
        this.instructorRepository.saveAndFlush(bill);

        deactivate(this.john.getId(), billToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(StatusCode.SUCCESS));
    }

    @Test
    @DisplayName("3a: deactivating a student who is already deactivated is a conflict and changes nothing")
    void deactivatingAnAlreadyDeactivatedStudentIsAConflict() throws Exception {
        String billToken = login("b.gates@abc.edu");
        deactivate(this.john.getId(), billToken).andExpect(status().isOk());

        deactivate(this.john.getId(), billToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.flag").value(false))
                .andExpect(jsonPath("$.code").value(StatusCode.CONFLICT))
                .andExpect(jsonPath("$.message").value("The student is already deactivated."));

        assertThat(this.studentRepository.findById(this.john.getId()).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("3b: the course admin of a different course is denied")
    void courseAdminOfAnotherCourseIsDenied() throws Exception {
        // Wei administers COSC 40993 and CITE 30363, not EE 30323
        deactivate(this.jana.getId(), login("b.wei@abc.edu"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));

        assertThat(this.studentRepository.findById(this.jana.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("3b: an instructor of a different course section is denied")
    void instructorOfAnotherCourseSectionIsDenied() throws Exception {
        deactivate(this.john.getId(), login("t.cook@abc.edu"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));

        assertThat(this.studentRepository.findById(this.john.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("3b: a student is denied, deactivating a teammate or herself")
    void studentIsDenied() throws Exception {
        String johnToken = login("j.smith@abc.edu");

        deactivate(this.eric.getId(), johnToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
        deactivate(this.john.getId(), johnToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));

        assertThat(this.studentRepository.findById(this.eric.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(this.studentRepository.findById(this.john.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    @DisplayName("6a: a student id that names no student is denied like an unauthorized request, not a server error")
    void unknownStudentIsForbidden() throws Exception {
        deactivate(NO_SUCH_ID, login("b.wei@abc.edu"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(StatusCode.FORBIDDEN));
    }

}
