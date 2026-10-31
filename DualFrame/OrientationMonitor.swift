import CoreMotion

/// Rileva come è tenuto il telefono con l'accelerometro (funziona anche col blocco rotazione attivo).
/// L'angolo è la rotazione oraria rispetto alla verticale: 0, 90, 180, 270.
final class OrientationMonitor {
    private let motion = CMMotionManager()
    private(set) var angle = 0
    var onChange: ((Int) -> Void)?

    func start() {
        guard motion.isAccelerometerAvailable, !motion.isAccelerometerActive else { return }
        motion.accelerometerUpdateInterval = 0.1
        motion.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let self, let a = data?.acceleration else { return }
            var next = self.angle
            // Isteresi: cambia solo quando un asse domina chiaramente.
            if abs(a.y) > abs(a.x) + 0.3 {
                next = a.y < 0 ? 0 : 180
            } else if abs(a.x) > abs(a.y) + 0.3 {
                next = a.x > 0 ? 90 : 270
            }
            if next != self.angle {
                self.angle = next
                self.onChange?(next)
            }
        }
    }

    func stop() {
        motion.stopAccelerometerUpdates()
    }
}
