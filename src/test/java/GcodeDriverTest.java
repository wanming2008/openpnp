import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.machine.reference.ReferenceActuator;
import org.openpnp.machine.reference.ReferenceMachine;
import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.machine.reference.driver.GcodeDriver.CommandType;
import org.openpnp.machine.reference.driver.TcpCommunications;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Actuator;
import org.openpnp.spi.Machine;
import org.openpnp.util.GcodeServer;
import org.pmw.tinylog.Logger;

import com.google.common.io.Files;

public class GcodeDriverTest {
    GcodeServer server;
    
    @BeforeEach
    public void before() throws Exception {
        /**
         * Uncomment this to enable TRACE logging, which will show Gcode commands and responses.
         */
//        Configurator.currentConfig()
//                    .formatPattern("{date:yyyy-MM-dd HH:mm:ss.SSS} {class_name} {level}: {message}")
//                    .level(Level.TRACE)
//                    .activate();

        /**
         * Set up a TCP based GcodeServer that we'll connect to from GcodeDriver and create
         * responses for the connect, enable, and home commands. 
         */
        server = new GcodeServer();
        server.addCommandResponse("G21 ; Set millimeters mode", "ok");
        server.addCommandResponse("G90 ; Set absolute positioning mode", "ok");
        server.addCommandResponse("M82 ; Set absolute mode for extruder", "ok");
        server.addCommandResponse("G28 ; Home all axes", "ok");
        server.addCommandResponse("M400 ; Wait for moves to complete before returning", "ok");

        /**
         * Create a new config directory and load the default configuration.
         */
        File workingDirectory = Files.createTempDir();
        workingDirectory = new File(workingDirectory, ".openpnp");
        System.out.println("Configuration directory: " + workingDirectory);
        Configuration.initialize(workingDirectory);
        Configuration.get().load();

        /**
         * Create a GcodeDriver and set it up to connect to the GcodeServer that was just created.
         * We also lower some timeouts to make the tests run faster, as responses on localhost
         * should be very fast.
         */
        GcodeDriver driver = new GcodeDriver();
        driver.createDefaults();
        driver.setConnectionKeepAlive(false);
        driver.setCommunicationsType(CommunicationsType.tcp);
        TcpCommunications tcp = (TcpCommunications) driver.getCommunications();
        tcp.setIpAddress("localhost");
        tcp.setPort(server.getListenerPort());
        driver.setConnectWaitTimeMilliseconds(0);
        driver.setTimeoutMilliseconds(500);
        
        /**
         * Configure the machine to use the new GcodeDriver and initialize the machine.
         */
        ReferenceMachine referenceMachine = (ReferenceMachine) Configuration.get().getMachine();
        while (referenceMachine.getDrivers().size() > 0) {
            referenceMachine.removeDriver(referenceMachine.getDrivers().get(0));
        }
        referenceMachine.addDriver(driver);
        
        /**
         * Start the machine.
         */
        Machine machine = Configuration.get().getMachine();
        machine.setEnabled(true);
        machine.home();
    }

    @Test
    public void testActuatorRead() throws Exception {
        Machine machine = Configuration.get().getMachine();
        Actuator actuator = new ReferenceActuator();
        actuator.setName("A1");
        machine.addActuator(actuator);
        GcodeDriver driver = (GcodeDriver) ((ReferenceMachine) machine).getDefaultDriver();
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_COMMAND, "READ A1");
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_REGEX, "read:a1:(?<Value>-?\\d+)");

        server.addCommandResponse("READ A1", "read:a1:497\nok");
        
        /**
         * Read the actuator we configured.
         */
        assertEquals(machine.execute(() -> actuator.read()), "497");
    }
    
    @Test
    public void testActuatorReadNoRegex() throws Exception {
        Machine machine = Configuration.get().getMachine();
        Actuator actuator = new ReferenceActuator();
        actuator.setName("A1");
        machine.addActuator(actuator);
        GcodeDriver driver = (GcodeDriver) ((ReferenceMachine) machine).getDefaultDriver();
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_COMMAND, "READ A1");

        server.addCommandResponse("READ A1", "read:a1:497\nok");
        
        /**
         * Attempt to read the actuator we configured. It should throw because we didn't set an
         * ACTUATOR_READ_REGEX.
         */
        try {
            machine.execute(() -> actuator.read());
            throw new AssertionError("Expected Actuator.read() to fail because no regex set.");
        }
        catch (Exception e) {
        }
    }
    
    @Test
    public void testActuatorReadNoCommand() throws Exception {
        Machine machine = Configuration.get().getMachine();
        Actuator actuator = new ReferenceActuator();
        actuator.setName("A1");
        machine.addActuator(actuator);
        GcodeDriver driver = (GcodeDriver) ((ReferenceMachine) machine).getDefaultDriver();
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_REGEX, "read:a1:(?<Value>-?\\d+)");

        server.addCommandResponse("READ A1", "read:a1:497\nok");
        
        /**
         * Attempt to read the actuator we configured. It should fail because we didn't set an
         * ACTUATOR_READ_COMMAND.
         */
        try {
            machine.execute(() -> actuator.read());
            throw new AssertionError("Expected Actuator.read() to fail because no command set.");
        }
        catch (Exception e) {
        }
    }
    
    @Test
    public void testActuatorReadBadRegex() throws Exception {
        Machine machine = Configuration.get().getMachine();
        Actuator actuator = new ReferenceActuator();
        actuator.setName("A1");
        machine.addActuator(actuator);
        GcodeDriver driver = (GcodeDriver) ((ReferenceMachine) machine).getDefaultDriver();
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_COMMAND, "READ A1");
        driver.setCommand(actuator, CommandType.ACTUATOR_READ_REGEX, "reXXad:a1:(?<Value>-?\\d+)");

        server.addCommandResponse("READ A1", "read:a1:497\nok");
        
        /**
         * Attempt to read the actuator we configured. It should fail because the 
         * ACTUATOR_READ_REGEX is incorrect and will not match the response.
         */
        try {
            machine.execute(() -> actuator.read());
            throw new AssertionError("Expected Actuator.read() to fail because invalid regex set.");
        }
        catch (Exception e) {
        }
    }
    
    @AfterEach
    public void after() throws Exception {
        Logger.info("Shutdown");
        /**
         * Stop the machine.
         */
        Machine machine = Configuration.get().getMachine();
        machine.setEnabled(false);

        server.shutdown();
    }
}
