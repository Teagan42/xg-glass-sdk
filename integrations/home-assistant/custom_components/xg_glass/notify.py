"""Notify platform for xg.glass Smart Glasses.

Implements a NotifyEntity so Home Assistant automations, scripts, and
services can push arbitrary text (and optional title) to the glasses
display at any time.

Mechanism
---------
When `notify.send_message` is called on this entity, the entity fires an
``xg_glass_display`` event on the HA event bus.  The glasses companion app
(XgGlassHaBridgeEntry.kt) maintains a persistent WebSocket subscription
to this event type and renders the message on the glasses display immediately.
"""
from __future__ import annotations

import logging

from homeassistant.components.notify import NotifyEntity, NotifyEntityFeature
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from . import device_info
from .const import ATTR_MESSAGE, ATTR_TITLE, CONF_DEVICE_NAME, DOMAIN, EVENT_DISPLAY

_LOGGER = logging.getLogger(__name__)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: ConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up xg.glass notify entities."""
    async_add_entities([XgGlassNotifyEntity(hass, entry)])


class XgGlassNotifyEntity(NotifyEntity):
    """Notify entity that pushes text to the glasses display.

    The entity exposes the ``notify.send_message`` service.  When called,
    it fires ``xg_glass_display`` on the HA event bus so the glasses
    companion app can consume it immediately over its open WebSocket.
    """

    _attr_has_entity_name = True
    _attr_name = "Display"
    _attr_supported_features = NotifyEntityFeature.TITLE

    def __init__(self, hass: HomeAssistant, entry: ConfigEntry) -> None:
        self._hass = hass
        self._entry = entry
        self._attr_unique_id = f"{entry.entry_id}_display"
        self._attr_device_info = device_info(entry)

    async def async_send_message(self, message: str, title: str | None = None) -> None:
        """Push a message to the glasses display via the HA event bus."""
        payload: dict = {
            "entry_id": self._entry.entry_id,
            ATTR_MESSAGE: message,
        }
        if title:
            payload[ATTR_TITLE] = title

        self._hass.bus.async_fire(EVENT_DISPLAY, payload)
        _LOGGER.debug(
            "Pushed display message to %s: %s",
            self._entry.data[CONF_DEVICE_NAME],
            message,
        )
