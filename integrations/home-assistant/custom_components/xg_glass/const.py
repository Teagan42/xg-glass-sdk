"""Constants for the xg.glass Smart Glasses integration."""

DOMAIN = "xg_glass"

# Config entry keys
CONF_DEVICE_NAME = "device_name"

# Event fired on the HA event bus to push display text to the glasses.
# The glasses companion app subscribes to this event type via HA WebSocket.
EVENT_DISPLAY = f"{DOMAIN}_display"

# Service registered by this integration so automations can push text to the
# glasses without going through the notify entity.
SERVICE_DISPLAY = "display"
ATTR_MESSAGE = "message"
ATTR_TITLE = "title"
