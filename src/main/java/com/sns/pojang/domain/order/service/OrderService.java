package com.sns.pojang.domain.order.service;

import com.sns.pojang.domain.member.entity.Member;
import com.sns.pojang.domain.member.exception.MemberNotFoundException;
import com.sns.pojang.domain.member.repository.MemberRepository;
import com.sns.pojang.domain.menu.entity.Menu;
import com.sns.pojang.domain.menu.entity.MenuOption;
import com.sns.pojang.domain.menu.exception.MenuNotFoundException;
import com.sns.pojang.domain.menu.exception.MenuOptionNotFoundException;
import com.sns.pojang.domain.menu.repository.MenuOptionRepository;
import com.sns.pojang.domain.menu.repository.MenuRepository;
import com.sns.pojang.domain.order.dto.request.OrderRequest;
import com.sns.pojang.domain.order.dto.request.SelectedMenuRequest;
import com.sns.pojang.domain.order.dto.response.CountResponse;
import com.sns.pojang.domain.order.dto.response.CreateOrderResponse;
import com.sns.pojang.domain.order.dto.response.OrderResponse;
import com.sns.pojang.domain.order.entity.Order;
import com.sns.pojang.domain.order.entity.OrderMenu;
import com.sns.pojang.domain.order.entity.OrderStatus;
import com.sns.pojang.domain.order.exception.InvalidTotalPriceException;
import com.sns.pojang.domain.order.exception.OrderAlreadyCanceledException;
import com.sns.pojang.domain.order.exception.OrderNotFoundException;
import com.sns.pojang.domain.order.repository.OrderRepository;
import com.sns.pojang.domain.store.entity.Store;
import com.sns.pojang.domain.store.exception.StoreNotFoundException;
import com.sns.pojang.domain.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.List;
import java.util.stream.Collectors;

import static com.sns.pojang.global.error.ErrorCode.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {
    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;
    private final MenuOptionRepository menuOptionRepository;

    // 메뉴 주문
    @Transactional
    public CreateOrderResponse createOrder(Long storeId, OrderRequest orderRequest) {
        Member findMember = findMember();
        Store findStore = findStore(storeId);

        // 총 주문 금액 검증
        validateTotalPrice(orderRequest.getSelectedMenus(), orderRequest.getTotalPrice());
        Order order = orderRequest.toEntity(findMember, findStore);

        for (SelectedMenuRequest selectedMenu : orderRequest.getSelectedMenus()){
            Menu findMenu = findMenu(selectedMenu.getMenuId());
            validateMenu(findStore, findMenu);
            OrderMenu orderMenu = OrderMenu.builder()
                    .quantity(selectedMenu.getQuantity())
                    .menu(findMenu)
                    .build();
            // 메뉴 옵션이 있으면 아래 코드 실행
            if (selectedMenu.getSelectedMenuOptions() != null){
                for (Long optionId : selectedMenu.getSelectedMenuOptions()){
                    MenuOption findMenuOption = findMenuOption(optionId);
                    findMenuOption.attachOrderMenu(orderMenu);
                }
            }
            orderMenu.attachOrder(order);
        }
        return CreateOrderResponse.from(orderRepository.save(order));
    }

    // 주문 취소
    @Transactional
    public OrderResponse cancelOrder(Long storeId, Long orderId){
        Member findMember = findMember();
        Order findOrder = findOrder(orderId);
        Store findStore = findStore(storeId);
        validateOrder(findMember, findOrder);
        validateStore(findStore, findOrder);

        if (findOrder.getOrderStatus() == OrderStatus.CANCELED){
            throw new OrderAlreadyCanceledException();
        }
        findOrder.updateOrderStatus(OrderStatus.CANCELED);
        return OrderResponse.from(findOrder);
    }

    // 주문 상세 조회
    @Transactional
    public OrderResponse getOrderDetail(Long storeId, Long orderId) {
        Member findMember = findMember();
        Order findOrder = findOrder(orderId);
        Store findStore = findStore(storeId);
        validateOrder(findMember, findOrder);
        validateStore(findStore, findOrder);

        return OrderResponse.from(findOrder);
    }

    @Transactional
    public List<OrderResponse> getOrders(Long storeId, Pageable pageable) {
        Store findStore = findStore(storeId);
        validateOwner(findStore);
        Page<Order> orders = orderRepository.findByStore(findStore, pageable);

        return orders.stream().map(OrderResponse::from).collect(Collectors.toList());
    }

    // 프론트에서 받아온 총 주문 금액 검증 (프론트는 중간에 연산이 조작 되기 쉬움)
    private void validateTotalPrice(List<SelectedMenuRequest> selectedMenus, int totalPrice){
        int calculatedTotalPrice = 0;
        for (SelectedMenuRequest menuRequest : selectedMenus){
            Menu menu = findMenu(menuRequest.getMenuId());
            int menuOptionTotal = 0;
            if (menuRequest.getSelectedMenuOptions() != null){
                for (Long menuOptionId : menuRequest.getSelectedMenuOptions()){
                    MenuOption menuOption = findMenuOption(menuOptionId);
                    log.info("옵션 금액: " + menuOption.getPrice());
                    menuOptionTotal += menuOption.getPrice();
                }
            }
            calculatedTotalPrice += menu.getPrice() * menuRequest.getQuantity() + menuOptionTotal;
        }
        if (calculatedTotalPrice != totalPrice){
            throw new InvalidTotalPriceException();
        }
    }

    private void validateOrder(Member member, Order order){
        if (!order.getMember().equals(member)){
            throw new AccessDeniedException(MEMBER_ORDER_MISMATCH.getMessage());
        }
    }

    private void validateStore(Store store, Order order){
        if (!order.getStore().equals(store)){
            throw new AccessDeniedException(STORE_ORDER_MISMATCH.getMessage());
        }
    }

    // 가게 등록한 Owner인지 검증
    private void validateOwner(Store store){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Member findMember = memberRepository.findByEmail(authentication.getName())
                .orElseThrow(MemberNotFoundException::new);
        if (!store.getMember().equals(findMember)){
            throw new AccessDeniedException(store.getName() + "의 사장님이 아닙니다.");
        }
    }

    // menu의 store와 입력된 store의 일치 여부 확인
    private void validateMenu(Store store, Menu menu){
        if (!menu.getStore().equals(store)){
            throw new AccessDeniedException(STORE_MENU_MISMATCH.getMessage());
        }
    }

    private Member findMember(){
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return memberRepository.findByEmail(authentication.getName())
                .orElseThrow(MemberNotFoundException::new);
    }

    private Store findStore(Long storeId){
        return storeRepository.findById(storeId)
                .orElseThrow(StoreNotFoundException::new);
    }

    private Order findOrder(Long orderId){
        return orderRepository.findById(orderId)
                .orElseThrow(OrderNotFoundException::new);
    }

    private Menu findMenu(Long menuId){
        return menuRepository.findById(menuId)
                .orElseThrow(MenuNotFoundException::new);
    }

    private MenuOption findMenuOption(Long menuOptionId){
        return menuOptionRepository.findById(menuOptionId)
                .orElseThrow(MenuOptionNotFoundException::new);
    }

    public CountResponse getCount(Long storeId) {
        Store store = storeRepository.findById(storeId).orElseThrow(StoreNotFoundException::new);
        List<Order> orders = orderRepository.findByStoreAndOrderStatus(store, OrderStatus.CONFIRM);
        return CountResponse.from(store, orders.size());
    }
}
