package team.projectpulse.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<PeerEvaluationUser, Integer> {

    Optional<PeerEvaluationUser> findByUsername(String username);

    Optional<PeerEvaluationUser> findByEmail(String email);

    List<PeerEvaluationUser> findByEmailIn(Collection<String> emails);

    /**
     * Whether the account exists and is active (FR-SEC-active-account). A single existence query, so the
     * per-request check loads no entity and none of its eager associations.
     */
    boolean existsByIdAndEnabledTrue(Integer id);

}
