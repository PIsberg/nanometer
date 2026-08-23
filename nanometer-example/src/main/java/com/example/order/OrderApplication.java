package com.example.order;

import nanometer.Nanometer;

/**
 * Main runnable entrypoint demonstrating automatic runtime APM attachment.
 */
public class OrderApplication {

    public static void main(String[] args) throws Exception {
        System.out.println("⚡ Starting Example Order Application with Nanometer APM...");

        // 1. Install Nanometer agent dynamically for com.example
        Nanometer.install("com.example");

        // 2. Launch embedded visualizer dashboard on port 9090
        Nanometer.startVisualizer(9090);

        System.out.println("⚡ [Nanometer] Embedded APM Dashboard live at http://localhost:9090");
        System.out.println("⚡ Generating continuous synthetic traffic (Ctrl+C to stop)...");

        // 3. Initial burst of traffic
        runSimulation(20, 50);

        // 4. Continuous background traffic generator to keep metrics fresh
        Thread trafficThread = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(2000);
                    runSimulation(5, 50);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        // 5. Keep main process alive
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            trafficThread.interrupt();
            Nanometer.shutdown();
            System.out.println("⚡ OrderApplication stopped.");
        }));

        // Block main thread to keep HTTP server active
        Thread.currentThread().join();
    }

    public static void runSimulation(int iterations, int sleepMs) {
        OrderService orderService = new OrderService();
        String[] customers = {"cust_alice", "cust_bob", "fraud", "timeout", "cust_charlie"};
        String[] items = {"laptop", "phone", "out-of-stock", "tablet", "watch"};

        for (int i = 0; i < iterations; i++) {
            String cust = customers[i % customers.length];
            String item = items[i % items.length];
            try {
                orderService.placeOrder(cust, item, 49.99);
            } catch (Exception ignored) {
                // Expected simulation errors tracked in APM
            }
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
