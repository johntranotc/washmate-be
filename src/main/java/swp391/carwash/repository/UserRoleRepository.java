package swp391.carwash.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import swp391.carwash.entity.UserRole;
import swp391.carwash.enums.RoleName;

public interface UserRoleRepository extends JpaRepository<UserRole, Integer> {

    List<UserRole> findByUserId(Integer userId);

    List<UserRole> findByUserIdAndRoleRoleNameIn(Integer userId, Collection<RoleName> roleNames);
}
