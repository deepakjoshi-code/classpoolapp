package app.classpool.api.repository;

import app.classpool.api.domain.OrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {

    List<OrderLine> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}
