package com.familyfinance.notification;
import java.util.*;
import org.springframework.data.jpa.repository.*;
public interface NotificationRepository extends JpaRepository<Notification,Long> {
 @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
 @Query("select n from Notification n where n.household.id=:household and n.type=:type and n.referenceType=:referenceType and n.referenceId=:referenceId and ((:userId is null and n.user is null) or n.user.id=:userId)")
 List<Notification> currentIdentity(long household,NotificationType type,String referenceType,long referenceId,Long userId);
 Optional<Notification> findByTypeAndReferenceTypeAndReferenceIdAndUserId(NotificationType type,String referenceType,long referenceId,Long userId);
 @Query("select n from Notification n where n.household.id=:householdId and (n.user is null or n.user.id=:userId) order by n.resolvedAt asc, n.dueAt asc, n.id asc") List<Notification> visible(long householdId,long userId);
 @Query("select count(n) from Notification n where n.household.id=:householdId and (n.user is null or n.user.id=:userId) and n.readAt is null and n.resolvedAt is null") long unread(long householdId,long userId);
 @Query("select n from Notification n where n.household.id=:householdId and n.referenceType=:referenceType and n.referenceId=:referenceId and n.resolvedAt is null") List<Notification> openReference(long householdId,String referenceType,long referenceId);
 Optional<Notification> findByIdAndHouseholdId(Long id,Long householdId);
}
