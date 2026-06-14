package com.example.demo;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    public record TradeRequest(String ticker, double quantity, double price, String action) {}

    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @GetMapping("/suggestions")
    public List<StockService.Suggestion> suggestions() {
        return service.suggestions();
    }

    @GetMapping("/portfolio")
    public StockService.Portfolio portfolio() {
        return service.portfolio();
    }

    @PostMapping("/trade")
    public StockService.Portfolio trade(@RequestBody TradeRequest req) {
        return service.recordTrade(req.ticker(), req.quantity(), req.price(), req.action());
    }

    @DeleteMapping("/holding/{ticker}")
    public StockService.Portfolio remove(@PathVariable String ticker) {
        return service.removeHolding(ticker);
    }
}
